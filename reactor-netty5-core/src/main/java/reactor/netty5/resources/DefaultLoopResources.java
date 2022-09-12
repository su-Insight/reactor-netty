/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5.resources;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.nio.NioHandler;
import io.netty5.util.concurrent.FastThreadLocalThread;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.NonBlocking;

/**
 * An adapted global eventLoop handler.
 *
 * @since 0.6
 */
final class DefaultLoopResources extends AtomicLong implements LoopResources {

	final String                          prefix;
	final boolean                         daemon;
	final int                             selectCount;
	final int                             workerCount;
	final AtomicReference<EventLoopGroup> serverLoops;
	final AtomicReference<EventLoopGroup> clientLoops;
	final AtomicReference<EventLoopGroup> serverSelectLoops;
	final AtomicReference<EventLoopGroup> cacheNativeClientLoops;
	final AtomicReference<EventLoopGroup> cacheNativeServerLoops;
	final AtomicReference<EventLoopGroup> cacheNativeSelectLoops;
	final AtomicBoolean                   running;

	DefaultLoopResources(String prefix, int workerCount, boolean daemon) {
		this(prefix, -1, workerCount, daemon);
	}

	DefaultLoopResources(String prefix, int selectCount, int workerCount, boolean daemon) {
		this.running = new AtomicBoolean(true);
		this.daemon = daemon;
		this.workerCount = workerCount;
		this.prefix = prefix;

		this.serverLoops = new AtomicReference<>();
		this.clientLoops = new AtomicReference<>();

		this.cacheNativeClientLoops = new AtomicReference<>();
		this.cacheNativeServerLoops = new AtomicReference<>();

		if (selectCount == -1) {
			this.selectCount = workerCount;
			this.serverSelectLoops = this.serverLoops;
			this.cacheNativeSelectLoops = this.cacheNativeServerLoops;
		}
		else {
			this.selectCount = selectCount;
			this.serverSelectLoops = new AtomicReference<>();
			this.cacheNativeSelectLoops = new AtomicReference<>();
		}
	}

	@Override
	public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
		return Mono.defer(() -> {
			long quietPeriodMillis = quietPeriod.toMillis();
			long timeoutMillis = timeout.toMillis();

			EventLoopGroup serverLoopsGroup = serverLoops.get();
			EventLoopGroup clientLoopsGroup = clientLoops.get();
			EventLoopGroup serverSelectLoopsGroup = serverSelectLoops.get();
			EventLoopGroup cacheNativeClientGroup = cacheNativeClientLoops.get();
			EventLoopGroup cacheNativeSelectGroup = cacheNativeSelectLoops.get();
			EventLoopGroup cacheNativeServerGroup = cacheNativeServerLoops.get();

			Mono<?> clMono = Mono.empty();
			Mono<?> sslMono = Mono.empty();
			Mono<?> slMono = Mono.empty();
			Mono<?> cnclMono = Mono.empty();
			Mono<?> cnslMono = Mono.empty();
			Mono<?> cnsrvlMono = Mono.empty();
			if (running.compareAndSet(true, false)) {
				if (clientLoopsGroup != null) {
					clMono = Mono.fromCompletionStage(clientLoopsGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS).asStage());
				}
				if (serverSelectLoopsGroup != null) {
					sslMono = Mono.fromCompletionStage(serverSelectLoopsGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS).asStage());
				}
				if (serverLoopsGroup != null) {
					slMono = Mono.fromCompletionStage(serverLoopsGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS).asStage());
				}
				if (cacheNativeClientGroup != null) {
					cnclMono = Mono.fromCompletionStage(cacheNativeClientGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS).asStage());
				}
				if (cacheNativeSelectGroup != null) {
					cnslMono = Mono.fromCompletionStage(cacheNativeSelectGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS).asStage());
				}
				if (cacheNativeServerGroup != null) {
					cnsrvlMono = Mono.fromCompletionStage(cacheNativeServerGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS).asStage());
				}
			}

			return Mono.when(clMono, sslMono, slMono, cnclMono, cnslMono, cnsrvlMono);
		});
	}

	@Override
	public boolean isDisposed() {
		return !running.get();
	}

	@Override
	public EventLoopGroup onClient(boolean useNative) {
		if (useNative && LoopResources.hasNativeSupport()) {
			return cacheNativeClientLoops();
		}
		return cacheNioClientLoops();
	}

	@Override
	public EventLoopGroup onServer(boolean useNative) {
		if (useNative && LoopResources.hasNativeSupport()) {
			return cacheNativeServerLoops();
		}
		return cacheNioServerLoops();
	}

	@Override
	public EventLoopGroup onServerSelect(boolean useNative) {
		if (useNative && LoopResources.hasNativeSupport()) {
			return cacheNativeSelectLoops();
		}
		return cacheNioSelectLoops();
	}

	@Override
	public String toString() {
		return "DefaultLoopResources {" +
				"prefix=" + prefix +
				", daemon=" + daemon +
				", selectCount=" + selectCount +
				", workerCount=" + workerCount +
				'}';
	}

	EventLoopGroup cacheNioClientLoops() {
		EventLoopGroup eventLoopGroup = clientLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = LoopResources.colocate(cacheNioServerLoops());
			if (!clientLoops.compareAndSet(null, newEventLoopGroup)) {
				// Do not shutdown newEventLoopGroup as this will shutdown the server loops
			}
			eventLoopGroup = cacheNioClientLoops();
		}
		return eventLoopGroup;
	}

	EventLoopGroup cacheNioSelectLoops() {
		if (serverSelectLoops == serverLoops) {
			return cacheNioServerLoops();
		}

		EventLoopGroup eventLoopGroup = serverSelectLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = new MultithreadEventLoopGroup(selectCount,
					threadFactory(this, "select-nio"), NioHandler.newFactory());
			if (!serverSelectLoops.compareAndSet(null, newEventLoopGroup)) {
				newEventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
			}
			eventLoopGroup = cacheNioSelectLoops();
		}
		return eventLoopGroup;
	}

	EventLoopGroup cacheNioServerLoops() {
		EventLoopGroup eventLoopGroup = serverLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = new MultithreadEventLoopGroup(workerCount,
					threadFactory(this, "nio"), NioHandler.newFactory());
			if (!serverLoops.compareAndSet(null, newEventLoopGroup)) {
				newEventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
			}
			eventLoopGroup = cacheNioServerLoops();
		}
		return eventLoopGroup;
	}

	EventLoopGroup cacheNativeClientLoops() {
		EventLoopGroup eventLoopGroup = cacheNativeClientLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = LoopResources.colocate(cacheNativeServerLoops());
			if (!cacheNativeClientLoops.compareAndSet(null, newEventLoopGroup)) {
				// Do not shutdown newEventLoopGroup as this will shutdown the server loops
			}
			eventLoopGroup = cacheNativeClientLoops();
		}
		return eventLoopGroup;
	}

	EventLoopGroup cacheNativeSelectLoops() {
		if (cacheNativeSelectLoops == cacheNativeServerLoops) {
			return cacheNativeServerLoops();
		}

		EventLoopGroup eventLoopGroup = cacheNativeSelectLoops.get();
		if (null == eventLoopGroup) {
			DefaultLoop defaultLoop = DefaultLoopNativeDetector.INSTANCE;
			EventLoopGroup newEventLoopGroup = defaultLoop.newEventLoopGroup(
					selectCount,
					threadFactory(this, "select-" + defaultLoop.getName()));
			if (!cacheNativeSelectLoops.compareAndSet(null, newEventLoopGroup)) {
				newEventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
			}
			eventLoopGroup = cacheNativeSelectLoops();
		}
		return eventLoopGroup;
	}

	EventLoopGroup cacheNativeServerLoops() {
		EventLoopGroup eventLoopGroup = cacheNativeServerLoops.get();
		if (null == eventLoopGroup) {
			DefaultLoop defaultLoop = DefaultLoopNativeDetector.INSTANCE;
			EventLoopGroup newEventLoopGroup = defaultLoop.newEventLoopGroup(
					workerCount,
					threadFactory(this, defaultLoop.getName()));
			if (!cacheNativeServerLoops.compareAndSet(null, newEventLoopGroup)) {
				newEventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
			}
			eventLoopGroup = cacheNativeServerLoops();
		}
		return eventLoopGroup;
	}

	static ThreadFactory threadFactory(DefaultLoopResources parent, String prefix) {
		return new EventLoopFactory(parent.daemon, parent.prefix + "-" + prefix, parent);
	}

	static final class EventLoop extends FastThreadLocalThread implements NonBlocking {

		EventLoop(Runnable target) {
			super(target);
		}
	}

	static final class EventLoopFactory implements ThreadFactory {

		final boolean    daemon;
		final AtomicLong counter;
		final String     prefix;

		EventLoopFactory(boolean daemon, String prefix, AtomicLong counter) {
			this.daemon = daemon;
			this.counter = counter;
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new EventLoop(r);
			t.setDaemon(daemon);
			t.setName(prefix + "-" + counter.incrementAndGet());
			return t;
		}
	}
}