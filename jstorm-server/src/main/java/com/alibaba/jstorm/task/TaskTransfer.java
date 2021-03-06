package com.alibaba.jstorm.task;

import java.util.Map;

import org.apache.log4j.Logger;

import backtype.storm.Config;
import backtype.storm.messaging.TaskMessage;
import backtype.storm.serialization.KryoTupleSerializer;
import backtype.storm.tuple.TupleExt;
import backtype.storm.utils.DisruptorQueue;
import backtype.storm.utils.Utils;
import backtype.storm.utils.WorkerClassLoader;

import com.alibaba.jstorm.callback.AsyncLoopThread;
import com.alibaba.jstorm.callback.RunnableCallback;
import com.alibaba.jstorm.daemon.worker.WorkerData;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.SingleThreadedClaimStrategy;
import com.lmax.disruptor.WaitStrategy;

/**
 * Sending entrance
 * 
 * Task sending all tuples through this Object
 * 
 * Serialize the Tuple and put the serialized data to the sending queue
 * 
 * @author yannian
 * 
 */
public class TaskTransfer {

	private static Logger LOG = Logger.getLogger(TaskTransfer.class);

	private Map storm_conf;
	private DisruptorQueue transferQueue;
	private KryoTupleSerializer serializer;
	private Map<Integer, DisruptorQueue> innerTaskTransfer;
	private DisruptorQueue serializeQueue;
	private final AsyncLoopThread   serializeThread;
	private volatile TaskStatus        taskStatus;

	public TaskTransfer(KryoTupleSerializer serializer, TaskStatus taskStatus,
			WorkerData workerData) {

		this.serializer = serializer;
		this.taskStatus = taskStatus;
		this.storm_conf = workerData.getConf();
		this.transferQueue = workerData.getTransferQueue();
		this.innerTaskTransfer = workerData.getInnerTaskTransfer();

		int queue_size = Utils.getInt(storm_conf
				.get(Config.TOPOLOGY_TRANSFER_BUFFER_SIZE));
		WaitStrategy waitStrategy = (WaitStrategy) Utils
				.newInstance((String) storm_conf
						.get(Config.TOPOLOGY_DISRUPTOR_WAIT_STRATEGY));
		this.serializeQueue = new DisruptorQueue(
				new SingleThreadedClaimStrategy(queue_size), waitStrategy);

		serializeThread = new AsyncLoopThread(new TransferRunnable());
		LOG.info("Successfully start TaskTransfer thread");

	}

	public void transfer(TupleExt tuple) {

		int taskid = tuple.getTargetTaskId();

		DisruptorQueue disrutporQueue = innerTaskTransfer.get(taskid);
		if (disrutporQueue != null) {
			disrutporQueue.publish(tuple);
		} else {
			serializeQueue.publish(tuple);
		}

	}
	
	

	public AsyncLoopThread getSerializeThread() {
		return serializeThread;
	}



	class TransferRunnable extends RunnableCallback implements EventHandler {

		@Override
		public void run() {
		
			WorkerClassLoader.switchThreadContext();
			while (taskStatus.isShutdown() == false) {
				serializeQueue.consumeBatchWhenAvailable(this);

			}
			WorkerClassLoader.restoreThreadContext();
		}

		public Object getResult() {
			if (taskStatus.isShutdown() == false ) {
				return 0;
			}else {
				return -1;
			}
		}

		@Override
		public void onEvent(Object event, long sequence, boolean endOfBatch)
				throws Exception {

			if (event == null) {
				return;
			}

			TupleExt tuple = (TupleExt) event;
			int taskid = tuple.getTargetTaskId();
			byte[] tupleMessage = serializer.serialize(tuple);
			TaskMessage taskMessage = new TaskMessage(taskid, tupleMessage);
			transferQueue.publish(taskMessage);

		}

	}

}
