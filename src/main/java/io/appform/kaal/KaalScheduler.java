/*
 * Copyright 2023. Santanu Sinha
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and limitations
 * under the License.
 */

package io.appform.kaal;

import io.appform.signals.signals.ConsumingSyncSignal;
import io.appform.signals.signals.ScheduledSignal;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * A scheduler that triggers {@link KaalTask}s at periodic intervals. One a run is completed, a signal is triggered
 * which the calling system can connect to, in order to do further processing on the results if necessary.
 * A task is identified by a task ID and a particular run of the task is identified by RunID. The RunID for a run is
 * generated by making a call to {@link KaalTaskRunIdGenerator#generateId(KaalTask, Date)}.
 * The delay to next execution is calculated by calling {@link KaalTask#delayToNextRun(Date)}.
 * Once a run is completed, whether a subsequent run will be scheduled or not is determined by making a call to
 * {@link KaalTaskStopStrategy#scheduleNext(KaalTaskData)}.
 * NOTE:
 * - Tasks might be delayed by {@link KaalScheduler#pollingInterval} milliseconds.
 * - Kaal tried to adjust for drift while scheduling next run, however if run time is close to the run interval,
 * behaviour might become unpredictable.
 * - Task execution will stop if delay() returns a negative value
 * - By default an unlimited cached thread pool is used to run tasks. The recommendation is to keep it that way.
 */
@Slf4j
public final class KaalScheduler<T extends KaalTask<T, R>, R> {

    private static final String HANDLER_NAME = "TASK_POLLER";

    private final long pollingInterval;
    private final KaalTaskRunIdGenerator<T, R> taskIdGenerator;
    private final KaalTaskStopStrategy<T, R> stopStrategy;
    private final ExecutorService executorService;

    private final PriorityBlockingQueue<KaalTaskData<T, R>> tasks
            = new PriorityBlockingQueue<>(1024,
                                          Comparator.comparing(e -> {
                                              val nextTime = e.getTargetExecutionTime().getTime();
                                              log.debug("Execution time: {}", nextTime);
                                              return nextTime;
                                          }));
    private final ScheduledSignal signalGenerator;

    private final Set<String> deleted = new ConcurrentSkipListSet<>();

    private final ConsumingSyncSignal<KaalTaskData<T, R>> taskCompleted = new ConsumingSyncSignal<>();

    KaalScheduler(
            long pollingInterval,
            KaalTaskRunIdGenerator<T, R> taskIdGenerator,
            KaalTaskStopStrategy<T, R> stopStrategy,
            ExecutorService executorService) {
        this.pollingInterval = pollingInterval;
        this.taskIdGenerator = taskIdGenerator;
        this.executorService = executorService;
        this.stopStrategy = stopStrategy;
        this.signalGenerator = ScheduledSignal.builder()
                .errorHandler(e -> log.error("Error running scheduled poll: " + e.getMessage(), e))
                .interval(Duration.ofMillis(pollingInterval))
                .build();
    }

    /**
     * Get a builder for the scheduler. Currently, this is the only way to create a scheduler
     *
     * @param <T> Implementation type for a {@link KaalTask}
     * @param <R> Return value from the task
     * @return A fully manifested Scheduler instance. Remember to call {@link #start()} to start the scheduler.
     */
    public static <T extends KaalTask<T, R>, R> KaalSchedulerBuilder<T, R> builder() {
        return new KaalSchedulerBuilder<>();
    }

    /**
     * Start the scheduler
     */
    public void start() {
        taskCompleted.connect(this::handleTaskCompletion);
        signalGenerator.connect(this::processQueuedTask);
        clear();
        log.info("Started task scheduler");
    }

    /**
     * Stop the scheduler
     */
    public void stop() {
        signalGenerator.disconnect(HANDLER_NAME);
        signalGenerator.close();
        log.info("Kaal scheduler shut down");
    }

    /**
     * Clear the scheduler to remove any pending task runs
     */
    public void clear() {
        tasks.clear();
        deleted.clear();
        log.info("Scheduler queue purged");
    }

    /**
     * Signal that gets invoked when a task run completes. Please call connect() on this signal to connect your handler.
     * Please note that all handlers oin this signal will be called in sequence. So it is better to avoid any
     * long-running code in these
     *
     * @return A reference to a synchronized signal
     */
    public ConsumingSyncSignal<KaalTaskData<T, R>> onTaskCompleted() {
        return taskCompleted;
    }

    /**
     * Schedule a task.
     *
     * @param task Task to be scheduled. Must inherit from {@link KaalTask}
     * @return Returns id for first run
     */
    public Optional<String> schedule(final T task) {
        return schedule(task, new Date());
    }

    /**
     * Schedule a task. Additionally, takes the reference time as a parameter.
     * Reference times are used to control the timings of subsequent runs. The first run will be after
     * reference time + delay calculated using {@link KaalTask#delayToNextRun(Date)}
     *
     * @param task     Task to be scheduled. Must inherit from {@link KaalTask}
     * @param currTime Reference time to be used to calculate subsequent runs
     * @return Returns id for first run
     */
    public Optional<String> schedule(final T task, final Date currTime) {
        var delay = task.delayToNextRun(currTime);
        if (delay < 0) {
            log.info("Received negative delay, will not schedule a run for the task {}", task.id());
            return Optional.empty();
        }
        if (delay < pollingInterval) {
            log.warn("Provided delay of {} ms readjusted to lowest possible delay of {} ms",
                     delay, pollingInterval);
            delay = pollingInterval;
        }
        val executionTime = new Date(currTime.getTime() + delay);
        val runId = taskIdGenerator.generateId(task, executionTime);
        val result = schedule(task, executionTime, runId);

        log.debug("Scheduled task {} with delay of {} ms at {} with run id {}. Reference time: {}",
                task.id(), delay, executionTime, runId, currTime);
        return result;
    }

    /**
     * Schedule a task with the first run being at the provided time. Subsequent runs and run conditions as well as
     * run ids for them will proceed in the usual manner.
     * @param task  Task to be scheduled. Must inherit from {@link KaalTask}
     * @param currTime Time for first run.
     * @return Returns id for first run
     */
    public Optional<String> scheduleAt(final T task, final Date currTime) {
        val runId = taskIdGenerator.generateId(task, currTime);
        return schedule(task, currTime, runId);
    }


    /**
     * Schedule a task with the first run to be executed right now. Subsequent runs and run conditions as well as
     * run ids for them will proceed in the usual manner.
     * @param task  Task to be scheduled. Must inherit from {@link KaalTask}
     * @return Returns id for first run
     */
    public Optional<String> scheduleNow(final T task) {
        return scheduleAt(task, new Date());
    }

    /**
     * Low level task scheduling. This can be typically used to recover runs from a permanent storage on restarts or
     * other recovery conditions. Subsequent runs and run conditions as well as run ids for them will proceed in the
     * usual manner. Avoid using this for other purposes.
     *
     * @param task          Task to be scheduled. Must inherit from {@link KaalTask}
     * @param executionTime Time for first run and the reference time to be used for subsequent runs
     * @param runId Unique ID for the current run
     * @return Returns id for first run
     */
    public Optional<String> schedule(
            final T task,
            final Date executionTime,
            final String runId) {
        tasks.put(new KaalTaskData<>(runId, task, executionTime));
        log.debug("A run for {} with run id {} has been scheduled at {}", task.id(), runId, executionTime);
        return Optional.of(runId);
    }

    /**
     * Delete a task from the scheduler. This does not guarantee removal. It guarantees that a further tun will not be
     * done.  A run that is already underway will not be interrupted.
     * @param id Id for the task to be deleted
     */
    public void delete(final String id) {
        deleted.add(id);
    }

    private void handleTaskCompletion(KaalTaskData<T, R> taskData) {
        val taskId = taskData.getTask().id();
        if (null == taskData.getException()) {
            log.info("Task run {}/{} is now complete.", taskId, taskData.getRunId());
        }
        else {
            log.warn("Task run {}/{} is now complete with error: {}",
                     taskId,
                     taskData.getRunId(),
                     errorMessage(taskData.getException()));
        }
        if (deleted.contains(taskId)) { //Will get hit if deleted during task execution
            log.debug("Looks like task {} has already been deleted .. no further scheduling necessary", taskId);
            deleted.remove(taskId);
            return;
        }
        if (!stopStrategy.scheduleNext(taskData)) {
            log.info("Task {} will not be scheduled further as stop strategy returned false", taskId);
            return;
        }
        val drift = taskData.drift();
        log.debug("Adjusting next run of {} for a drift of {} ms", taskId, drift);
        schedule(taskData.getTask(), Date.from(Instant.now().minus(drift, ChronoUnit.MILLIS)));
    }

    private void processQueuedTask(Date currentTime) {
        while (true) {
            val taskData = tasks.peek();
            var canContinue = false;
            if (taskData == null) {
                log.trace("Nothing queued... will sleep again");
            }
            else {
                val taskId = taskData.getTask().id();
                val runId = taskData.getRunId();
                log.trace("Received task {}/{}", taskId, runId);
                if (currentTime.before(taskData.getTargetExecutionTime())) {
                    log.trace("Found non-executable earliest task: {}/{}", taskId, runId);
                }
                else {
                    canContinue = true;
                }
            }
            if (!canContinue) {
                log.trace("Nothing to do now, will try again later.");
                break;
            }
            try {
                val taskId = taskData.getTask().id();
                val runId = taskData.getRunId();
                if (deleted.contains(taskId)) { //Will get hit if delete was called during pause
                    log.debug("Looks like task {} has already been deleted .. run {} will be ignored", taskId, runId);
                    deleted.remove(taskId);
                }
                else {
                    executorService.submit(() -> executeTask(taskData));
                    log.debug("{}/{} submitted for execution", taskId, runId);
                }
                val status = tasks.remove(taskData);
                log.trace("task run for {}/{} acked with status: {}", taskId, runId, status);
            }
            catch (Exception e) {
                log.error("Error scheduling topology task: ", e);
            }
        }
    }

    private void executeTask(KaalTaskData<T, R> taskData) {
        taskData.setActualStartTime(new Date());
        try {
            taskCompleted.dispatch(
                    taskData.setResult(taskData.getTask().apply(new Date(), taskData)));
        }
        catch (Throwable t) {
            taskCompleted.dispatch(taskData.setException(t));
        }
    }

    private static String errorMessage(Throwable t) {
        var root = t;
        while (null != root.getCause()) {
            root = root.getCause();
        }
        return null == root.getMessage()
               ? root.getClass().getSimpleName()
               : root.getMessage();
    }
}
