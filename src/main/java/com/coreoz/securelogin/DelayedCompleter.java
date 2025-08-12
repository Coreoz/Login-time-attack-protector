package com.coreoz.securelogin;

import java.time.Duration;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A utility class that provides a non-blocking wait mechanism.
 * <p>
 * This class is designed to handle a high volume of requests for delayed
 * future completions without creating a new thread for each request. It uses a
 * single, dedicated worker thread that sleeps efficiently until the next future
 * is due to be completed.
 * <p>
 * The internal implementation uses a {@link PriorityQueue} to store pending futures,
 * ordered by their scheduled completion time. This ensures the worker thread only
 * needs to check the item at the head of the queue.
 */
public final class DelayedCompleter implements AutoCloseable {
    private static AtomicInteger INSTANCE_NUMBER = new AtomicInteger(1);

    /**
     * A record to hold a future and its target completion time.
     * Implements Comparable to allow sorting in the PriorityQueue.
     */
    private record DelayedFuture(long completionTime, CompletableFuture<Void> future) implements Comparable<DelayedFuture> {
        @Override
        public int compareTo(DelayedFuture other) {
            return Long.compare(this.completionTime, other.completionTime);
        }
    }

    // A priority queue to store futures, ordered by the earliest completion time.
    // This is the core data structure, allowing O(1) access to the next item to expire.
    private final PriorityQueue<DelayedFuture> queue = new PriorityQueue<>();

    // The single worker thread that will manage completing the futures.
    private final Thread workerThread;

    // A flag to signal the worker thread to terminate gracefully.
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Constructs a new DelayedCompleter and starts its worker thread.
     * The worker thread is a daemon thread, so it won't prevent the JVM from exiting.
     */
    public DelayedCompleter() {
        this.workerThread = new Thread(this::workerLoop, "delayed-completer-worker-#" + INSTANCE_NUMBER.getAndIncrement());
        // We do not want the JVM to stay up only for this waiting tool
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    /**
     * Submits a request to complete a {@link CompletableFuture} after a specified duration.
     * This method is non-blocking and thread-safe.
     *
     * @param timeToWait The {@link Duration} to wait before completing the future.
     * @return A {@link CompletableFuture<Void>} that will be completed after the duration has passed.
     */
    public CompletableFuture<Void> waitDuration(Duration timeToWait) {
        if (!running.get()) {
            throw new IllegalStateException("DelayedCompleter has been shut down.");
        }

        final long completionTime = System.currentTimeMillis() + timeToWait.toMillis();
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final DelayedFuture delayedFuture = new DelayedFuture(completionTime, future);

        // Synchronize on the queue to safely add a new item and notify the worker.
        synchronized (queue) {
            queue.add(delayedFuture);
            // Notify the worker thread. It might be waiting, and this new item
            // could be the next one to expire, so it needs to re-evaluate its wait time.
            queue.notify();
        }

        return future;
    }

    /**
     * The main loop for the worker thread.
     * It waits for the precise amount of time needed for the next future to complete.
     */
    private void workerLoop() {
        while (running.get()) {
            DelayedFuture futureToComplete = null;

            // Synchronize on the queue to safely inspect and modify it.
            synchronized (queue) {
                try {
                    // If the queue is empty, wait indefinitely until a new item is added.
                    while (queue.isEmpty() && running.get()) {
                        queue.wait();
                    }

                    // If we were woken up to shut down, exit the loop.
                    if (!running.get()) {
                        break;
                    }

                    // Peek at the next future without removing it.
                    DelayedFuture nextFuture = queue.peek();
                    long delayMillis = nextFuture.completionTime() - System.currentTimeMillis();

                    if (delayMillis <= 0) {
                        // The future is due or overdue. Remove it from the queue to be completed.
                        futureToComplete = queue.poll();
                    } else {
                        // The future is not due yet. Wait for the exact duration.
                        queue.wait(delayMillis);
                    }
                } catch (InterruptedException e) {
                    // This can happen if close() is called. The while(running.get())
                    // condition will handle termination.
                    Thread.currentThread().interrupt(); // Preserve the interrupted status
                }
            } // End of synchronized block

            // Complete the future *outside* the synchronized block.
            // This is crucial to avoid holding the lock while potentially running
            // long-running downstream tasks attached to the future.
            if (futureToComplete != null) {
                futureToComplete.future().complete(null);
            }
        }
        // Clean up any remaining futures on shutdown
        drainQueue();
    }

    /**
     * Shuts down the worker thread and completes any pending futures exceptionally.
     * This implements the AutoCloseable interface for use in try-with-resources.
     */
    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            synchronized (queue) {
                // Wake up the worker thread if it's waiting.
                queue.notify();
            }
            try {
                // Wait for the worker thread to finish its current loop and terminate.
                workerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Drains the queue on shutdown, completing all pending futures exceptionally.
     */
    private void drainQueue() {
        synchronized (queue) {
            while (!queue.isEmpty()) {
                DelayedFuture pending = queue.poll();
                if (pending != null) {
                    pending.future().completeExceptionally(new IllegalStateException("DelayedCompleter was shut down."));
                }
            }
        }
    }
}
