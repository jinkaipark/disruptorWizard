package net.symphonious.disrupter.dsl;

import com.lmax.disruptor.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** A DSL-style wizard for setting up the disruptor pattern around a ring buffer.
 *
 * <p>A simple example of setting up the disruptor with two consumers that must process
 * events in order:</p>
 *
 * <pre><code> DisruptorWizard<MyEntry> dw = new DisruptorWizard<MyEntry>(MyEntry.FACTORY, 32, Executors.newCachedThreadPool());
 * BatchHandler<MyEntry> handler1 = new BatchHandler<MyEntry>() { ... };
 * BatchHandler<MyEntry> handler2 = new BatchHandler<MyEntry>() { ... };
 * dw.consumeWith(handler1);
 * dw.after(handler1).consumeWith(handler2);
 *
 * ProducerBarrier producerBarrier = dw.createProducerBarrier();</code></pre>
 *
 * @param <T> the type of {@link Entry} used.
 */
public class DisruptorWizard<T extends AbstractEntry>
{
    private final RingBuffer<T> ringBuffer;
    private final Executor executor;
    private final Map<BatchHandler, Consumer> consumers = new HashMap<BatchHandler, Consumer>();

    /** Create a new DisruptorWizard.
     *
     * @param entryFactory the factory to create entries in the ring buffer.
     * @param ringBufferSize the size of the ring buffer.
     * @param executor an {@link Executor} to execute consumers.
     */
    public DisruptorWizard(final EntryFactory<T> entryFactory, final int ringBufferSize, final Executor executor)
    {
        this.executor = executor;
        ringBuffer = new RingBuffer<T>(entryFactory, ringBufferSize);
    }

    /** Set up batch handlers to consume events from the ring buffer. These handlers will process events
     *  as soon as they become available, in parallel.
     *
     *  <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     *  process events before handler <code>B</code>:</p>
     *
     *  <pre><code>dw.consumeWith(A).then(B);</code></pre>
     *
     * @param handlers the batch handlers that will consume events.
     * @return a {@link ConsumerGroup} that can be used to set up a consumer barrier over the created consumers.
     */
    public ConsumerGroup<T> consumeWith(final BatchHandler<T>... handlers)
    {
        return createConsumers(new Consumer[0], handlers);
    }

    /** Specifies a group of consumers that can then be used to build a barrier for dependent consumers.
     * For example if the handler <code>A</code> must process events before handler <code>B</code>:
     *
     * <pre><code>dw.after(A).consumeWith(B);</code></pre>
     *
     * @param handlers the batch handlers, previously set up with {@link #consumeWith(com.lmax.disruptor.BatchHandler[])},
     * that will form the barrier for subsequent handlers.
     * @return a {@link ConsumerGroup} that can be used to setup a consumer barrier over the specified consumers.
     */
    public ConsumerGroup<T> after(final BatchHandler<T>... handlers)
    {
        Consumer[] selectedConsumers = new Consumer[handlers.length];
        for (int i = 0, handlersLength = handlers.length; i < handlersLength; i++)
        {
            final BatchHandler<T> handler = handlers[i];
            selectedConsumers[i] = consumers.get(handler);
            if (selectedConsumers[i] == null)
            {
                throw new IllegalArgumentException("Batch handlers must be consuming from the ring buffer before they can be used in a barrier condition.");
            }
        }
        return new ConsumerGroup<T>(this, selectedConsumers);
    }

    /** Create a producer barrier.  The barrier is set up to prevent overwriting any entry that is yet to
     * be processed by a consumer that has already been set up.  As such, producer barriers should be
     * created as the last step, after all handlers have been set up.
     *
     * @return the producer barrier.
     */
    public ProducerBarrier<T> createProducerBarrier()
    {
        return ringBuffer.createProducerBarrier(consumers.values().toArray(new Consumer[consumers.size()]));
    }

    /** Calls {@link com.lmax.disruptor.Consumer#halt()} on all of the consumers created via this wizard.
     */
    public void halt()
    {
        for (Consumer consumer : consumers.values())
        {
            consumer.halt();
        }
    }

    ConsumerGroup<T> createConsumers(final Consumer[] barrierConsumers, final BatchHandler<T>[] batchHandlers)
    {
        final Consumer[] createdConsumers = new Consumer[batchHandlers.length];
        for (int i = 0, batchHandlersLength = batchHandlers.length; i < batchHandlersLength; i++)
        {
            final BatchHandler<T> batchHandler = batchHandlers[i];
            final ConsumerBarrier<T> barrier = ringBuffer.createConsumerBarrier(barrierConsumers);
            final BatchConsumer<T> batchConsumer = new BatchConsumer<T>(barrier, batchHandler);
            consumers.put(batchHandler, batchConsumer);
            createdConsumers[i] = batchConsumer;
            executor.execute(batchConsumer);
        }
        return new ConsumerGroup<T>(this, createdConsumers);
    }
}