/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.transport;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty5.channel.EventLoop;
import io.netty5.util.concurrent.SingleThreadEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.netty5.Connection;
import reactor.netty5.DisposableServer;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.tcp.TcpClient;
import reactor.netty5.tcp.TcpServer;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty5.Metrics.EVENT_LOOP_PREFIX;
import static reactor.netty5.Metrics.NAME;
import static reactor.netty5.Metrics.PENDING_TASKS;

/**
 * Tests for event loop metrics
 *
 * @author Pierre De Rop
 * @since 1.0.14
 */
class TransportEventLoopMetricsTest {

	private MeterRegistry registry;
	final static Logger log = Loggers.getLogger(TransportEventLoopMetricsTest.class);

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	void testEventLoopMetrics() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		DisposableServer server = null;
		Connection client = null;
		LoopResources loop = null;

		try {
			loop = LoopResources.create(TransportEventLoopMetricsTest.class.getName(), 3, true);
			server = TcpServer.create()
					.port(0)
					.metrics(true)
					.runOn(loop)
					.doOnConnection(c -> {
						EventLoop eventLoop = c.channel().executor();
						IntStream.range(0, 10).forEach(i -> eventLoop.execute(() -> {}));
						if (eventLoop instanceof SingleThreadEventExecutor singleThreadEventExecutor) {
							String[] tags = new String[0];
							try {
								tags = new String[]{
										NAME, singleThreadEventExecutor.threadProperties().name(),
								};
							}
							catch (InterruptedException e) {
								log.warn("operation interrupted", e);
								return;
							}
							// 10 tasks added by us, and a listener for child channel registration
							assertThat(getGaugeValue(EVENT_LOOP_PREFIX + PENDING_TASKS, tags)).isEqualTo(11);
							latch.countDown();
						}
					})
					.wiretap(true)
					.bindNow();

			assertThat(server).isNotNull();

			client = TcpClient.create()
					.port(server.port())
					.wiretap(true)
					.connectNow();

			assertThat(client).isNotNull();
			assertThat(latch.await(5, TimeUnit.SECONDS)).as("Did not find 10 pending tasks from meter").isTrue();
		}

		finally {
			if (client != null) {
				client.disposeNow();
			}
			if (server != null) {
				server.disposeNow();
			}
			if (loop != null) {
				loop.disposeLater().block(Duration.ofSeconds(10));
			}
		}
	}

	// https://github.com/reactor/reactor-netty/issues/2187
	@Test
	void testEventLoopMetricsFailure() throws InterruptedException {
		registry.config().meterFilter(new MeterFilter() {
			@Override
			public Meter.Id map(Meter.Id id) {
				throw new IllegalArgumentException("Test injected Exception");
			}
		});

		final CountDownLatch latch = new CountDownLatch(1);
		DisposableServer server = null;
		Connection client = null;
		LoopResources loop = null;

		try {
			loop = LoopResources.create(TransportEventLoopMetricsTest.class.getName(), 3, true);
			server = TcpServer.create()
					.port(0)
					.metrics(true)
					.runOn(loop)
					.doOnConnection(c -> latch.countDown())
					.bindNow();

			assertThat(server).isNotNull();
			client = TcpClient.create()
					.port(server.port())
					.connectNow();

			assertThat(client).isNotNull();
			assertThat(latch.await(5, TimeUnit.SECONDS)).as("Failed to connect").isTrue();
		}

		finally {
			if (client != null) {
				client.disposeNow();
			}
			if (server != null) {
				server.disposeNow();
			}
			if (loop != null) {
				loop.disposeLater().block(Duration.ofSeconds(10));
			}
		}
	}

	private double getGaugeValue(String name, String... tags) {
		Gauge gauge = registry.find(name).tags(tags).gauge();
		double result = -1;
		if (gauge != null) {
			result = gauge.value();
		}
		return result;
	}

}