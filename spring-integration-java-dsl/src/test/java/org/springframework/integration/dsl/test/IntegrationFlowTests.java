/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.dsl.test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.mongodb.MongoClient;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.SimpleMongoDbFactory;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.MessageDispatchingException;
import org.springframework.integration.MessageRejectedException;
import org.springframework.integration.annotation.Header;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.annotation.MessageEndpoint;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.AbstractPollableChannel;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.FixedSubscriberChannel;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.config.EnableMessageHistory;
import org.springframework.integration.config.GlobalChannelInterceptor;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.ResequencerSpec;
import org.springframework.integration.dsl.SplitterEndpointSpec;
import org.springframework.integration.dsl.amqp.Amqp;
import org.springframework.integration.dsl.channel.DirectChannelSpec;
import org.springframework.integration.dsl.channel.MessageChannels;
import org.springframework.integration.dsl.support.GenericHandler;
import org.springframework.integration.dsl.support.GenericSplitter;
import org.springframework.integration.dsl.support.Pollers;
import org.springframework.integration.endpoint.MethodInvokingMessageSource;
import org.springframework.integration.event.core.MessagingEvent;
import org.springframework.integration.event.outbound.ApplicationEventPublishingMessageHandler;
import org.springframework.integration.file.DefaultFileNameGenerator;
import org.springframework.integration.file.FileHeaders;
import org.springframework.integration.file.FileWritingMessageHandler;
import org.springframework.integration.file.tail.ApacheCommonsFileTailingMessageProducer;
import org.springframework.integration.handler.advice.ExpressionEvaluatingRequestHandlerAdvice;
import org.springframework.integration.mongodb.store.MongoDbChannelMessageStore;
import org.springframework.integration.router.MethodInvokingRouter;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.integration.splitter.DefaultMessageSplitter;
import org.springframework.integration.store.MessageStore;
import org.springframework.integration.store.PriorityCapableChannelMessageStore;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.support.MutableMessageBuilder;
import org.springframework.integration.transformer.PayloadDeserializingTransformer;
import org.springframework.integration.transformer.PayloadSerializingTransformer;
import org.springframework.integration.xml.transformer.support.XPathExpressionEvaluatingHeaderValueMessageProcessor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.core.DestinationResolutionException;
import org.springframework.messaging.support.ChannelInterceptorAdapter;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * @author Artem Bilan
 */
@ContextConfiguration
@RunWith(SpringJUnit4ClassRunner.class)
@DirtiesContext
public class IntegrationFlowTests {

	private static final File tmpDir = new File(System.getProperty("java.io.tmpdir"));

	private static int mongoPort;

	private static MongodExecutable mongodExe;

	private static MongodProcess mongod;

	@Autowired
	private ListableBeanFactory beanFactory;

	@Autowired
	private ControlBusGateway controlBus;

	@Autowired
	@Qualifier("flow1QueueChannel")
	private AbstractPollableChannel outputChannel;

	@Autowired
	private TestChannelInterceptor testChannelInterceptor;

	@Autowired
	@Qualifier("inputChannel")
	private DirectChannel inputChannel;

	@Autowired
	@Qualifier("foo")
	private PublishSubscribeChannel foo;

	@Autowired
	@Qualifier("successChannel")
	private PollableChannel successChannel;

	@Autowired
	@Qualifier("flow3Input")
	private DirectChannel flow3Input;

	@Autowired
	private AtomicReference<Object> eventHolder;

	@Autowired
	@Qualifier("bridgeFlowInput")
	private PollableChannel bridgeFlowInput;

	@Autowired
	@Qualifier("bridgeFlowOutput")
	private PollableChannel bridgeFlowOutput;

	@Autowired
	@Qualifier("bridgeFlow2Input")
	private DirectChannel bridgeFlow2Input;

	@Autowired
	@Qualifier("bridgeFlow2Output")
	private PollableChannel bridgeFlow2Output;

	@Autowired
	@Qualifier("fileFlow1Input")
	private DirectChannel fileFlow1Input;

	@Autowired
	@Qualifier("fileWritingMessageHandler")
	private FileWritingMessageHandler fileWritingMessageHandler;

	@Autowired
	@Qualifier("methodInvokingInput")
	private DirectChannel methodInvokingInput;

	@Autowired
	@Qualifier("delayedAdvice")
	private DelayedAdvice delayedAdvice;

	@Autowired
	@Qualifier("enricherInput")
	private FixedSubscriberChannel enricherInput;

	@Autowired
	@Qualifier("splitInput")
	private DirectChannel splitInput;

	@Autowired
	@Qualifier("xpathHeaderEnricherInput")
	private DirectChannel xpathHeaderEnricherInput;

	@Autowired
	@Qualifier("splitAggregateInput")
	private MessageChannel splitAggregateInput;

	@Autowired
	@Qualifier("routerInput")
	private MessageChannel routerInput;

	@Autowired
	@Qualifier("oddChannel")
	private PollableChannel oddChannel;

	@Autowired
	@Qualifier("evenChannel")
	private PollableChannel evenChannel;

	@Autowired
	@Qualifier("routerMethodInput")
	private MessageChannel routerMethodInput;

	@Autowired
	@Qualifier("foo-channel")
	private PollableChannel fooChannel;

	@Autowired
	@Qualifier("bar-channel")
	private PollableChannel barChannel;

	@Autowired
	@Qualifier("routerMethod2Input")
	private MessageChannel routerMethod2Input;

	@Autowired
	@Qualifier("routerMethod3Input")
	private MessageChannel routerMethod3Input;

	@Autowired
	@Qualifier("routerMultiInput")
	private MessageChannel routerMultiInput;

	@Autowired
	@Qualifier("recipientListInput")
	private MessageChannel recipientListInput;

	@Autowired
	@Qualifier("defaultOutputChannel")
	private QueueChannel defaultOutputChannel;

	@Autowired
	private MessageStore messageStore;

	@Autowired
	@Qualifier("claimCheckInput")
	private MessageChannel claimCheckInput;

	@Autowired
	@Qualifier("priorityChannel")
	private MessageChannel priorityChannel;

	@Autowired
	@Qualifier("priorityReplyChannel")
	private PollableChannel priorityReplyChannel;

	@Autowired
	@Qualifier("lamdasInput")
	private MessageChannel lamdasInput;

	@Autowired
	@Qualifier("tailChannel")
	private PollableChannel tailChannel;

	@Autowired
	private AmqpTemplate amqpTemplate;

	@Autowired
	private Queue amqpQueue;

	@Autowired
	@Qualifier("gatewayInput")
	private MessageChannel gatewayInput;

	@Autowired
	@Qualifier("gatewayError")
	private PollableChannel gatewayError;

	@BeforeClass
	public static void setup() throws IOException {
		mongoPort = Network.getFreeServerPort();
		mongodExe = MongodStarter.getDefaultInstance()
				.prepare(new MongodConfigBuilder()
						.version(Version.Main.PRODUCTION)
						.net(new Net(mongoPort, Network.localhostIsIPv6()))
						.build());
		mongod = mongodExe.start();
	}

	@AfterClass
	public static void tearDown() {
		mongod.stop();
		mongodExe.stop();
	}

	@Test
	public void testPollingFlow() {
		assertThat(this.beanFactory.getBean("integerChannel"), Matchers.instanceOf(FixedSubscriberChannel.class));
		for (int i = 0; i < 10; i++) {
			Message<?> message = this.outputChannel.receive(5000);
			assertNotNull(message);
			assertEquals("" + i, message.getPayload());
		}

		assertTrue(this.outputChannel.getChannelInterceptors().contains(this.testChannelInterceptor));
		assertEquals(new Integer(10), this.testChannelInterceptor.getInvoked());
	}

	@Test
	public void testDirectFlow() {
		assertTrue(this.beanFactory.containsBean("filter"));
		assertTrue(this.beanFactory.containsBean("filter.handler"));
		QueueChannel replyChannel = new QueueChannel();
		Message<String> message = MessageBuilder.withPayload("100").setReplyChannel(replyChannel).build();
		try {
			this.inputChannel.send(message);
			fail("Expected MessageDispatchingException");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(MessageDeliveryException.class));
			assertThat(e.getCause(), Matchers.instanceOf(MessageDispatchingException.class));
			assertThat(e.getMessage(), Matchers.containsString("Dispatcher has no subscribers"));
		}
		this.controlBus.send("@payloadSerializingTransformer.start()");

		final AtomicBoolean used = new AtomicBoolean();

		this.foo.subscribe(m -> used.set(true));

		this.inputChannel.send(message);
		Message<?> reply = replyChannel.receive(5000);
		assertNotNull(reply);
		assertEquals(200, reply.getPayload());

		Message<?> successMessage = this.successChannel.receive(5000);
		assertNotNull(successMessage);
		assertEquals(100, successMessage.getPayload());

		assertTrue(used.get());
	}

	@Test
	public void testHandle() {
		assertNull(this.eventHolder.get());
		this.flow3Input.send(new GenericMessage<>("2"));
		assertNotNull(this.eventHolder.get());
		assertEquals(4, this.eventHolder.get());
	}

	@Test
	public void testBridge() {
		GenericMessage<String> message = new GenericMessage<>("test");
		this.bridgeFlowInput.send(message);
		Message<?> reply = this.bridgeFlowOutput.receive(5000);
		assertNotNull(reply);
		assertEquals("test", reply.getPayload());

		assertTrue(this.beanFactory.containsBean("bridgeFlow2:channel#0"));
		assertThat(this.beanFactory.getBean("bridgeFlow2:channel#0"), Matchers.instanceOf(FixedSubscriberChannel.class));

		try {
			this.bridgeFlow2Input.send(message);
			fail("Expected MessageDispatchingException");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(MessageDeliveryException.class));
			assertThat(e.getCause(), Matchers.instanceOf(MessageDispatchingException.class));
			assertThat(e.getMessage(), Matchers.containsString("Dispatcher has no subscribers"));
		}
		this.controlBus.send("@bridge.start()");
		this.bridgeFlow2Input.send(message);
		reply = this.bridgeFlow2Output.receive(5000);
		assertNotNull(reply);
		assertEquals("test", reply.getPayload());
		assertTrue(this.delayedAdvice.getInvoked());
	}

	@Test
	public void testWrongLastComponent() {
		ConfigurableApplicationContext context = null;
		try {
			context = new AnnotationConfigApplicationContext(InvalidLastComponentFlowContext.class);
			fail("BeanCreationException expected");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(BeanCreationException.class));
			assertThat(e.getMessage(), Matchers.containsString("is a one-way 'MessageHandler'"));
		}
		finally {
			if (context != null) {
				context.close();
			}
		}
	}

	@Test
	public void testWrongLastMessageChannel() {
		ConfigurableApplicationContext context = null;
		try {
			context = new AnnotationConfigApplicationContext(InvalidLastMessageChannelFlowContext.class);
			fail("BeanCreationException expected");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(BeanCreationException.class));
			assertThat(e.getMessage(), Matchers.containsString("'.fixedSubscriberChannel()' " +
					"can't be the last EIP-method in the IntegrationFlow definition"));
		}
		finally {
			if (context != null) {
				context.close();
			}
		}
	}


	@Test
	public void testFileHandler() {
		assertEquals(1, this.beanFactory.getBeansOfType(FileWritingMessageHandler.class).size());
		Message<?> message = MessageBuilder.withPayload("foo").setHeader(FileHeaders.FILENAME, "foo").build();
		try {
			this.fileFlow1Input.send(message);
			fail("NullPointerException expected");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(MessageHandlingException.class));
			assertThat(e.getCause(), Matchers.instanceOf(NullPointerException.class));
		}
		DefaultFileNameGenerator fileNameGenerator = new DefaultFileNameGenerator();
		fileNameGenerator.setBeanFactory(this.beanFactory);
		this.fileWritingMessageHandler.setFileNameGenerator(fileNameGenerator);
		this.fileFlow1Input.send(message);

		assertTrue(new File(tmpDir, "foo").exists());
	}

	@Test
	public void testMethodInvokingMessageHandler() {
		QueueChannel replyChannel = new QueueChannel();
		Message<?> message = MessageBuilder.withPayload("world")
				.setHeader(MessageHeaders.REPLY_CHANNEL, replyChannel)
				.build();
		this.methodInvokingInput.send(message);
		Message<?> receive = replyChannel.receive(5000);
		assertNotNull(receive);
		assertEquals("Hello, world", receive.getPayload());
	}

	@Test
	public void testLamdas() {
		QueueChannel replyChannel = new QueueChannel();
		Message<?> message = MessageBuilder.withPayload("World")
				.setHeader(MessageHeaders.REPLY_CHANNEL, replyChannel)
				.build();
		this.lamdasInput.send(message);
		Message<?> receive = replyChannel.receive(5000);
		assertNotNull(receive);
		assertEquals("Hello World", receive.getPayload());

		message = MessageBuilder.withPayload("Spring")
				.setHeader(MessageHeaders.REPLY_CHANNEL, replyChannel)
				.build();

		this.lamdasInput.send(message);
		assertNull(replyChannel.receive(10));

	}

	@Test
	public void testWrongConfigurationWithSpecBean() {
		ConfigurableApplicationContext context = null;
		try {
			context = new AnnotationConfigApplicationContext(InvalidConfigurationWithSpec.class);
			fail("BeanCreationException expected");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(IllegalArgumentException.class));
			assertThat(e.getCause(), Matchers.instanceOf(BeanCreationException.class));
			assertThat(e.getCause().getMessage(),
					Matchers.containsString("must be populated to target objects via 'get()' method call"));
		}
		finally {
			if (context != null) {
				context.close();
			}
		}
	}

	@Test
	public void testContentEnricher() {
		QueueChannel replyChannel = new QueueChannel();
		Message<?> message = MessageBuilder.withPayload(new TestPojo("Bar"))
				.setHeader(MessageHeaders.REPLY_CHANNEL, replyChannel)
				.build();
		this.enricherInput.send(message);
		Message<?> receive = replyChannel.receive(5000);
		assertNotNull(receive);
		assertEquals("Bar Bar", receive.getHeaders().get("foo"));
		Object payload = receive.getPayload();
		assertThat(payload, Matchers.instanceOf(TestPojo.class));
		TestPojo result = (TestPojo) payload;
		assertEquals("Bar Bar", result.getName());
		assertNotNull(result.getDate());
		assertThat(new Date(), Matchers.greaterThanOrEqualTo(result.getDate()));
	}

	@Test
	public void testSplitterResequencer() {
		QueueChannel replyChannel = new QueueChannel();

		this.splitInput.send(MessageBuilder.withPayload("")
				.setReplyChannel(replyChannel)
				.setHeader("foo", "bar")
				.build());

		for (int i = 0; i < 12; i++) {
			Message<?> receive = replyChannel.receive(2000);
			assertNotNull(receive);
			assertFalse(receive.getHeaders().containsKey("foo"));
			assertTrue(receive.getHeaders().containsKey("FOO"));
			assertEquals("BAR", receive.getHeaders().get("FOO"));
			assertEquals(i + 1, receive.getPayload());
		}
	}

	@Test
	public void testSplitterAggregator() {
		List<Character> payload = Arrays.asList('a', 'b', 'c', 'd', 'e');

		QueueChannel replyChannel = new QueueChannel();
		this.splitAggregateInput.send(MessageBuilder.withPayload(payload)
				.setReplyChannel(replyChannel)
				.build());

		Message<?> receive = replyChannel.receive(2000);
		assertNotNull(receive);
		assertThat(receive.getPayload(), Matchers.instanceOf(List.class));
		@SuppressWarnings("unchecked")
		List<Object> result = (List<Object>) receive.getPayload();
		for (int i = 0; i < payload.size(); i++) {
			assertEquals(payload.get(i), result.get(i));
		}
	}

	@Test
	public void testHeaderEnricher() {
		QueueChannel replyChannel = new QueueChannel();

		Message<String> message =
				MessageBuilder.withPayload("<root><elementOne>1</elementOne><elementTwo>2</elementTwo></root>")
						.setReplyChannel(replyChannel)
						.build();

		try {
			this.xpathHeaderEnricherInput.send(message);
			fail("Expected MessageDispatchingException");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(MessageDeliveryException.class));
			assertThat(e.getCause(), Matchers.instanceOf(MessageDispatchingException.class));
			assertThat(e.getMessage(), Matchers.containsString("Dispatcher has no subscribers"));
		}

		this.controlBus.send("@xpathHeaderEnricher.start()");
		this.xpathHeaderEnricherInput.send(message);

		Message<?> result = replyChannel.receive(2000);
		assertNotNull(result);
		MessageHeaders headers = result.getHeaders();
		assertEquals("1", headers.get("one"));
		assertEquals("2", headers.get("two"));
	}

	@Test
	public void testRouter() {

		int[] payloads = new int[] {1, 2, 3, 4, 5, 6};

		for (int payload : payloads) {
			this.routerInput.send(new GenericMessage<>(payload));
		}

		for (int i = 0; i < 3; i++) {
			Message<?> receive = this.oddChannel.receive(2000);
			assertNotNull(receive);
			assertEquals(i * 2 + 1, receive.getPayload());

			receive = this.evenChannel.receive(2000);
			assertNotNull(receive);
			assertEquals(i * 2 + 2, receive.getPayload());
		}

	}

	@Test
	public void testMethodInvokingRouter() {
		Message<String> fooMessage = new GenericMessage<>("foo");
		Message<String> barMessage = new GenericMessage<>("bar");
		Message<String> badMessage = new GenericMessage<>("bad");

		this.routerMethodInput.send(fooMessage);

		Message<?> result1a = this.fooChannel.receive(2000);
		assertNotNull(result1a);
		assertEquals("foo", result1a.getPayload());
		assertNull(this.barChannel.receive(0));

		this.routerMethodInput.send(barMessage);
		assertNull(this.fooChannel.receive(0));
		Message<?> result2b = this.barChannel.receive(2000);
		assertNotNull(result2b);
		assertEquals("bar", result2b.getPayload());

		try {
			this.routerMethodInput.send(badMessage);
			fail("MessageDeliveryException expected.");
		}
		catch (MessageDeliveryException e) {
			assertThat(e.getMessage(),
					Matchers.containsString("no channel resolved by router and no default output channel defined"));
		}

	}

	@Test
	public void testMethodInvokingRouter2() {
		Message<String> fooMessage = MessageBuilder.withPayload("foo").setHeader("targetChannel", "foo").build();
		Message<String> barMessage = MessageBuilder.withPayload("bar").setHeader("targetChannel", "bar").build();
		Message<String> badMessage = MessageBuilder.withPayload("bad").setHeader("targetChannel", "bad").build();

		this.routerMethod2Input.send(fooMessage);

		Message<?> result1a = this.fooChannel.receive(2000);
		assertNotNull(result1a);
		assertEquals("foo", result1a.getPayload());
		assertNull(this.barChannel.receive(0));

		this.routerMethod2Input.send(barMessage);
		assertNull(this.fooChannel.receive(0));
		Message<?> result2b = this.barChannel.receive(2000);
		assertNotNull(result2b);
		assertEquals("bar", result2b.getPayload());

		try {
			this.routerMethod2Input.send(badMessage);
			fail("DestinationResolutionException expected.");
		}
		catch (MessagingException e) {
			assertThat(e.getCause(), Matchers.instanceOf(DestinationResolutionException.class));
			assertThat(e.getCause().getMessage(),
					Matchers.containsString("failed to look up MessageChannel with name 'bad-channel'"));
		}

	}

	@Test
	public void testMethodInvokingRouter3() {
		Message<String> fooMessage = new GenericMessage<>("foo");
		Message<String> barMessage = new GenericMessage<>("bar");
		Message<String> badMessage = new GenericMessage<>("bad");

		this.routerMethod3Input.send(fooMessage);

		Message<?> result1a = this.fooChannel.receive(2000);
		assertNotNull(result1a);
		assertEquals("foo", result1a.getPayload());
		assertNull(this.barChannel.receive(0));

		this.routerMethod3Input.send(barMessage);
		assertNull(this.fooChannel.receive(0));
		Message<?> result2b = this.barChannel.receive(2000);
		assertNotNull(result2b);
		assertEquals("bar", result2b.getPayload());

		try {
			this.routerMethod3Input.send(badMessage);
			fail("DestinationResolutionException expected.");
		}
		catch (MessagingException e) {
			assertThat(e.getCause(), Matchers.instanceOf(DestinationResolutionException.class));
			assertThat(e.getCause().getMessage(),
					Matchers.containsString("failed to look up MessageChannel with name 'bad-channel'"));
		}
	}

	@Test
	public void testMultiRouter() {

		Message<String> fooMessage = new GenericMessage<>("foo");
		Message<String> barMessage = new GenericMessage<>("bar");
		Message<String> badMessage = new GenericMessage<>("bad");

		this.routerMultiInput.send(fooMessage);
		Message<?> result1a = this.fooChannel.receive(2000);
		assertNotNull(result1a);
		assertEquals("foo", result1a.getPayload());
		Message<?> result1b = this.barChannel.receive(2000);
		assertNotNull(result1b);
		assertEquals("foo", result1b.getPayload());

		this.routerMultiInput.send(barMessage);
		Message<?> result2a = this.fooChannel.receive(2000);
		assertNotNull(result2a);
		assertEquals("bar", result2a.getPayload());
		Message<?> result2b = this.barChannel.receive(2000);
		assertNotNull(result2b);
		assertEquals("bar", result2b.getPayload());

		try {
			this.routerMultiInput.send(badMessage);
			fail("MessageDeliveryException expected.");
		}
		catch (MessageDeliveryException e) {
			assertThat(e.getMessage(),
					Matchers.containsString("no channel resolved by router and no default output channel defined"));
		}
	}

	@Test
	public void testRecipientListRouter() {

		Message<String> fooMessage = MessageBuilder.withPayload("fooPayload").setHeader("recipient", true).build();
		Message<String> barMessage = MessageBuilder.withPayload("barPayload").setHeader("recipient", true).build();
		Message<String> badMessage = new GenericMessage<>("badPayload");

		this.recipientListInput.send(fooMessage);
		Message<?> result1a = this.fooChannel.receive(2000);
		assertNotNull(result1a);
		assertEquals("foo", result1a.getPayload());
		Message<?> result1b = this.barChannel.receive(2000);
		assertNotNull(result1b);
		assertEquals("foo", result1b.getPayload());

		this.recipientListInput.send(barMessage);
		assertNull(this.fooChannel.receive(0));
		Message<?> result2b = this.barChannel.receive(2000);
		assertNotNull(result2b);
		assertEquals("bar", result2b.getPayload());


		this.recipientListInput.send(badMessage);
		assertNull(this.fooChannel.receive(0));
		assertNull(this.barChannel.receive(0));
		Message<?> result3c = this.defaultOutputChannel.receive(2000);
		assertNotNull(result3c);
		assertEquals("bad", result3c.getPayload());

	}

	@Test
	public void testClaimCheck() {
		QueueChannel replyChannel = new QueueChannel();

		Message<String> message = MutableMessageBuilder.withPayload("foo").setReplyChannel(replyChannel).build();

		this.claimCheckInput.send(message);

		Message<?> receive = replyChannel.receive(2000);
		assertNotNull(receive);
		assertSame(message, receive);

		assertEquals(1, this.messageStore.getMessageCount());
		assertSame(message, this.messageStore.getMessage(message.getHeaders().getId()));
	}

	@Test
	public void testPriority() throws InterruptedException {

		Message<String> message = MessageBuilder.withPayload("1").setPriority(1).build();
		this.priorityChannel.send(message);

		message = MessageBuilder.withPayload("-1").setPriority(-1).build();
		this.priorityChannel.send(message);

		message = MessageBuilder.withPayload("3").setPriority(3).build();
		this.priorityChannel.send(message);

		message = MessageBuilder.withPayload("0").setPriority(0).build();
		this.priorityChannel.send(message);

		message = MessageBuilder.withPayload("2").setPriority(2).build();
		this.priorityChannel.send(message);

		message = MessageBuilder.withPayload("none").build();
		this.priorityChannel.send(message);

		message = MessageBuilder.withPayload("31").setPriority(3).build();
		this.priorityChannel.send(message);

		Thread.sleep(1000);

		Message<?> receive = this.priorityReplyChannel.receive(1000);
		assertNotNull(receive);
		assertEquals("3", receive.getPayload());

		receive = this.priorityReplyChannel.receive(1000);
		assertNotNull(receive);
		assertEquals("31", receive.getPayload());

		receive = this.priorityReplyChannel.receive(1000);
		assertNotNull(receive);
		assertEquals("2", receive.getPayload());

		receive = this.priorityReplyChannel.receive(1000);
		assertNotNull(receive);
		assertEquals("1", receive.getPayload());

		receive = this.priorityReplyChannel.receive(1000);
		assertNotNull(receive);
		assertEquals("0", receive.getPayload());

		receive = this.priorityReplyChannel.receive(1000);
		assertNotNull(receive);
		assertEquals("-1", receive.getPayload());

		receive = this.priorityReplyChannel.receive(1000);
		assertNotNull(receive);
		assertEquals("none", receive.getPayload());
	}

	@Test
	public void testMessageProducerFlow() throws Exception {
		FileOutputStream file = new FileOutputStream(new File(tmpDir, "TailTest"));
		for (int i = 0; i < 50; i++) {
			file.write((i + "\n").getBytes());
		}
		file.flush();
		file.close();

		for (int i = 0; i < 50; i++) {
			Message<?> message = this.tailChannel.receive(5000);
			assertNotNull(message);
			assertEquals("hello " + i, message.getPayload());
		}
		assertNull(this.tailChannel.receive(1));
	}


	@Test
	public void testAmqpInboundGatewayFlow() throws Exception {
		Object result = this.amqpTemplate.convertSendAndReceive(this.amqpQueue.getName(), "world");
		assertEquals("HELLO WORLD", result);
	}

	@Test
	public void testGatewayFlow() throws Exception {
		PollableChannel replyChannel = new QueueChannel();
		Message<String> message = MessageBuilder.withPayload("foo").setReplyChannel(replyChannel).build();

		this.gatewayInput.send(message);

		Message<?> receive = replyChannel.receive(2000);
		assertNotNull(receive);
		assertEquals("FOO", receive.getPayload());
		assertNull(this.gatewayError.receive(1));

		message = MessageBuilder.withPayload("bar").setReplyChannel(replyChannel).build();

		this.gatewayInput.send(message);

		receive = replyChannel.receive(1);
		assertNull(receive);

		receive = this.gatewayError.receive(2000);
		assertNotNull(receive);
		assertThat(receive, Matchers.instanceOf(ErrorMessage.class));
		assertThat(receive.getPayload(), Matchers.instanceOf(MessageRejectedException.class));
		assertThat(((Exception) receive.getPayload()).getMessage(), Matchers.containsString("' rejected Message"));
	}

	@MessagingGateway(defaultRequestChannel = "controlBus")
	private static interface ControlBusGateway {

		void send(String command);
	}

	@Configuration
	@EnableAutoConfiguration
	@IntegrationComponentScan
	public static class ContextConfiguration {

		@Bean
		public MessageSource<?> integerMessageSource() {
			MethodInvokingMessageSource source = new MethodInvokingMessageSource();
			source.setObject(new AtomicInteger());
			source.setMethodName("getAndIncrement");
			return source;
		}

		@Bean
		public IntegrationFlow controlBusFlow() {
			return IntegrationFlows.from("controlBus").controlBus().get();
		}

		@Bean
		public IntegrationFlow flow1() {
			return IntegrationFlows.from(this.integerMessageSource(), c -> c.poller(Pollers.fixedRate(100)))
					.fixedSubscriberChannel("integerChannel")
					.transform("payload.toString()")
					.channel(MessageChannels.queue("flow1QueueChannel"))
					.get();
		}

		@Bean(name = PollerMetadata.DEFAULT_POLLER)
		public PollerMetadata poller() {
			return Pollers.fixedRate(500).get();
		}

		@Bean
		public DirectChannel inputChannel() {
			return MessageChannels.direct().get();
		}

		@Bean
		public PublishSubscribeChannel foo() {
			return MessageChannels.publishSubscribe().get();
		}

	}

	@Configuration
	@ComponentScan
	public static class ContextConfiguration2 {

		@Autowired
		@Qualifier("inputChannel")
		private MessageChannel inputChannel;

		@Autowired
		@Qualifier("successChannel")
		private PollableChannel successChannel;


		@Bean
		public Advice expressionAdvice() {
			ExpressionEvaluatingRequestHandlerAdvice advice = new ExpressionEvaluatingRequestHandlerAdvice();
			advice.setOnSuccessExpression("payload");
			advice.setSuccessChannel(this.successChannel);
			return advice;
		}

		@Bean
		public IntegrationFlow flow2() {
			return IntegrationFlows.from(this.inputChannel)
					.filter(p -> p instanceof String, c -> c.id("filter"))
					.channel("foo")
					.fixedSubscriberChannel()
					.<String, Integer>transform(Integer::parseInt)
					.transform(new PayloadSerializingTransformer(),
							c -> c.autoStartup(false).id("payloadSerializingTransformer"))
					.channel(MessageChannels.queue(new SimpleMessageStore(), "fooQueue"))
					.transform(new PayloadDeserializingTransformer())
					.channel(MessageChannels.publishSubscribe("publishSubscribeChannel"))
					.transform((Integer p) -> p * 2, c -> c.advice(this.expressionAdvice()))
					.get();
		}

		@Bean
		public MongoDbFactory mongoDbFactory() throws Exception {
			return new SimpleMongoDbFactory(new MongoClient("localhost", mongoPort), "local");
		}

		@Bean
		public MongoDbChannelMessageStore mongoDbChannelMessageStore(MongoDbFactory mongoDbFactory) {
			MongoDbChannelMessageStore mongoDbChannelMessageStore = new MongoDbChannelMessageStore(mongoDbFactory);
			mongoDbChannelMessageStore.setPriorityEnabled(true);
			return mongoDbChannelMessageStore;
		}

		@Bean
		public IntegrationFlow priorityFlow(PriorityCapableChannelMessageStore mongoDbChannelMessageStore) {
			return IntegrationFlows.from(MessageChannels.priority("priorityChannel",
					mongoDbChannelMessageStore, "priorityGroup").interceptor())
					.bridge(s -> s.poller(Pollers.fixedDelay(1000, 2000)))
					.channel(MessageChannels.queue("priorityReplyChannel"))
					.get();
		}

	}

	@MessageEndpoint
	public static class AnnotationTestService {

		@ServiceActivator(inputChannel = "publishSubscribeChannel")
		public void handle(Object payload) {
			assertEquals(100, payload);
		}
	}

	@Configuration
	public static class ContextConfiguration3 {

		@Autowired
		@Qualifier("delayedAdvice")
		private MethodInterceptor delayedAdvice;

		@Bean
		public QueueChannel successChannel() {
			return MessageChannels.queue().get();
		}

		@Bean
		public AtomicReference<Object> eventHolder() {
			return new AtomicReference<>();
		}

		@Bean
		public ApplicationListener<MessagingEvent> eventListener() {
			return new ApplicationListener<MessagingEvent>() {

				@Override
				public void onApplicationEvent(MessagingEvent event) {
					eventHolder().set(event.getMessage().getPayload());
				}
			};
		}

		@Bean
		public IntegrationFlow flow3() {
			return IntegrationFlows.from("flow3Input")
					.handle(Integer.class, (p, h) -> p * 2)
					.handle(new ApplicationEventPublishingMessageHandler())
					.get();
		}

		@Bean
		public IntegrationFlow bridgeFlow() {
			return IntegrationFlows.from(MessageChannels.queue("bridgeFlowInput"))
					.channel(MessageChannels.queue("bridgeFlowOutput"))
					.get();
		}

		@Bean
		public IntegrationFlow bridgeFlow2() {
			return IntegrationFlows.from("bridgeFlow2Input")
					.bridge(c -> c.autoStartup(false).id("bridge"))
					.fixedSubscriberChannel()
					.delay("delayer", "200", c -> c.advice(this.delayedAdvice))
					.channel(MessageChannels.queue("bridgeFlow2Output"))
					.get();
		}

		@Bean
		public MessageStore messageStore() {
			return new SimpleMessageStore();
		}

		@Bean
		public IntegrationFlow claimCheckFlow() {
			return IntegrationFlows.from("claimCheckInput")
					.claimCheckIn(this.messageStore())
					.claimCheckOut(this.messageStore())
					.get();
		}

		@Bean(name = "foo-channel")
		public QueueChannel fooChannel() {
			return new QueueChannel();
		}

		@Bean(name = "bar-channel")
		public QueueChannel barChannel() {
			return new QueueChannel();
		}

		@Bean
		public QueueChannel defaultOutputChannel() {
			return new QueueChannel();
		}

		@Bean
		public IntegrationFlow recipientListFlow() {
			return IntegrationFlows.from("recipientListInput")
					.<String, String>transform(p -> p.replaceFirst("Payload", ""))
					.recipientListRoute(r ->
									r.defaultOutputChannel(defaultOutputChannel())
											.recipient("foo-channel", "'foo' == payload")
											.recipient("bar-channel", m ->
													m.getHeaders().containsKey("recipient")
															&& (boolean) m.getHeaders().get("recipient"))
					)
					.get();
		}
	}

	@Component("delayedAdvice")
	public static class DelayedAdvice implements MethodInterceptor {

		private final AtomicBoolean invoked = new AtomicBoolean();

		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {
			this.invoked.set(true);
			return invocation.proceed();
		}

		public Boolean getInvoked() {
			return invoked.get();
		}

	}

	@Configuration
	public static class ContextConfiguration4 {

		@Bean
		public FileWritingMessageHandler fileWritingMessageHandler() {
			return new FileWritingMessageHandler(tmpDir);
		}

		@Bean
		public IntegrationFlow fileFlow1() {
			return IntegrationFlows.from("fileFlow1Input")
					.handle(this.fileWritingMessageHandler(), c -> {
						FileWritingMessageHandler handler = c.get().getT2();
						handler.setFileNameGenerator(message -> null);
						handler.setExpectReply(false);
					})
					.get();
		}

		@Bean
		public IntegrationFlow methodInvokingFlow() {
			return IntegrationFlows.from("methodInvokingInput")
					.handle("greetingService", null)
					.get();
		}

		@Bean
		public IntegrationFlow lamdasFlow() {
			return IntegrationFlows.from("lamdasInput")
					.filter("World"::equals)
					.transform("Hello "::concat)
					.get();
		}

		@Bean
		public IntegrationFlow enricherFlow() {
			return IntegrationFlows.fromFixedMessageChannel("enricherInput")
					.enrich(e -> e.requestChannel("enrichChannel")
									.requestPayloadExpression("payload")
									.shouldClonePayload(false)
									.propertyExpression("name", "payload['name']")
									.propertyExpression("date", "new java.util.Date()")
									.headerExpression("foo", "payload['name']")
					)
					.get();
		}

		@Bean
		public IntegrationFlow enrichFlow() {
			return IntegrationFlows.from("enrichChannel")
					.<TestPojo, Map<?, ?>>transform(p -> Collections.singletonMap("name", p.getName() + " Bar"))
					.get();
		}

		@Bean
		public Executor taskExecutor() {
			return Executors.newCachedThreadPool();
		}

		@Bean
		public TestSplitterPojo testSplitterData() {
			List<String> first = new ArrayList<>();
			first.add("1,2,3");
			first.add("4,5,6");

			List<String> second = new ArrayList<>();
			second.add("7,8,9");
			second.add("10,11,12");

			return new TestSplitterPojo(first, second);
		}

		@Bean
		public IntegrationFlow splitResequenceFlow() {
			return IntegrationFlows.from("splitInput")
					.enrichHeaders(s -> s.header("FOO", "BAR"))
					.split("testSplitterData", "buildList", c -> c.applySequence(false))
					.channel(MessageChannels.executor(this.taskExecutor()))
					.split(Message.class, target -> (List<?>) target.getPayload(), c -> c.applySequence(false))
					.channel(MessageChannels.executor(this.taskExecutor()))
					.split(s -> s.applySequence(false).get().getT2().setDelimiters(","))
					.channel(MessageChannels.executor(this.taskExecutor()))
					.<String, Integer>transform(Integer::parseInt)
					.enrichHeaders(s -> s.headerExpression(IntegrationMessageHeaderAccessor.SEQUENCE_NUMBER, "payload"))
					.resequence((ResequencerSpec r) -> r.releasePartialSequences(true).correlationExpression("'foo'"))
					.headerFilter("foo", false)
					.get();
		}

		@Bean
		public IntegrationFlow splitAggregateFlow() {
			return IntegrationFlows.fromFixedMessageChannel("splitAggregateInput")
					.split(null)
					.channel(MessageChannels.executor(this.taskExecutor()))
					.resequence()
					.aggregate(null)
					.get();
		}

		@Bean
		public IntegrationFlow xpathHeaderEnricherFlow() {
			return IntegrationFlows.from("xpathHeaderEnricherInput")
					.enrichHeaders(
							s -> s.header("one", new XPathExpressionEvaluatingHeaderValueMessageProcessor("/root/elementOne"))
									.header("two", new XPathExpressionEvaluatingHeaderValueMessageProcessor("/root/elementTwo")),
							c -> c.autoStartup(false).id("xpathHeaderEnricher")
					)
					.get();
		}

		@Bean
		public QueueChannel oddChannel() {
			return new QueueChannel();
		}

		@Bean
		public QueueChannel evenChannel() {
			return new QueueChannel();
		}

		@Bean
		public IntegrationFlow routeFlow() {
			return IntegrationFlows.from("routerInput")
					.<Integer, Boolean>route(p -> p % 2 == 0,
							m -> m.suffix("Channel")
									.channelMapping("true", "even")
									.channelMapping("false", "odd"))
					.get();
		}

		@Bean
		public RoutingTestBean routingTestBean() {
			return new RoutingTestBean();
		}

		@Bean
		public IntegrationFlow routeMethodInvocationFlow() {
			return IntegrationFlows.from("routerMethodInput")
					.route("routingTestBean", "routeMessage")
					.get();
		}

		@Bean
		public IntegrationFlow routeMethodInvocationFlow2() {
			return IntegrationFlows.from("routerMethod2Input")
					.route(new MethodInvokingRouter(new RoutingTestBean(), "routeByHeader"))
					.get();
		}

		@Bean
		public IntegrationFlow routeMethodInvocationFlow3() {
			return IntegrationFlows.from("routerMethod3Input")
					.route((String p) -> ContextConfiguration4.this.routingTestBean().routePayload(p))
					.get();
		}

		@Bean
		public IntegrationFlow routeMultiMethodInvocationFlow() {
			return IntegrationFlows.from("routerMultiInput")
					.route(String.class, p -> p.equals("foo") || p.equals("bar") ? new String[] {"foo", "bar"} : null,
							s -> s.suffix("-channel"))
					.get();
		}

		@Bean
		public IntegrationFlow tailFlow() {
			ApacheCommonsFileTailingMessageProducer adapter = new ApacheCommonsFileTailingMessageProducer();
			adapter.setFile(new File(tmpDir, "TailTest"));

			return IntegrationFlows.from(adapter)
					.transform("hello "::concat)
					.channel(MessageChannels.queue("tailChannel"))
					.get();
		}

		@Autowired
		private ConnectionFactory rabbitConnectionFactory;

		@Bean
		public Queue queue() {
			return new AnonymousQueue();
		}

		@Bean
		public IntegrationFlow amqpFlow() {
			return IntegrationFlows.from(Amqp.inboundGateway(this.rabbitConnectionFactory, queue()).get())
					.transform("hello "::concat)
					.transform(String.class, String::toUpperCase)
					.get();
		}

		@Bean
		public IntegrationFlow gatewayFlow() {
			return IntegrationFlows.from("gatewayInput")
					.gateway("gatewayRequest", g -> g.errorChannel("gatewayError").replyTimeout(10L))
					.get();
		}

		@Bean
		public IntegrationFlow gatewayRequestFlow() {
			return IntegrationFlows.from("gatewayRequest")
					.filter("foo"::equals, f -> f.throwExceptionOnRejection(true))
					.<String, String>transform(String::toUpperCase)
					.get();
		}

		@Bean
		public MessageChannel gatewayError() {
			return MessageChannels.queue().get();
		}

	}

	private static class RoutingTestBean {

		public String routePayload(String name) {
			return name + "-channel";
		}

		public String routeByHeader(@Header("targetChannel") String name) {
			return name + "-channel";
		}

		public String routeMessage(Message<?> message) {
			if (message.getPayload().equals("foo")) {
				return "foo-channel";
			}
			else if (message.getPayload().equals("bar")) {
				return "bar-channel";
			}
			return null;
		}
	}

	@Component("greetingService")
	public static class GreetingService {

		public String greeting(String payload) {
			return "Hello, " + payload;
		}
	}


	private static class InvalidLastComponentFlowContext {

		@Bean
		public IntegrationFlow wrongLastComponent() {
			return IntegrationFlows.from(MessageChannels.direct())
					.route(Object::toString)
					.channel(MessageChannels.direct())
					.get();
		}

	}

	private static class InvalidLastMessageChannelFlowContext {

		@Bean
		public IntegrationFlow wrongLastComponent() {
			return IntegrationFlows.from(MessageChannels.direct())
					.fixedSubscriberChannel()
					.get();
		}

	}

	@EnableIntegration
	public static class InvalidConfigurationWithSpec {

		@Bean
		public DirectChannelSpec invalidBean() {
			return MessageChannels.direct();
		}

	}

	@Component
	@GlobalChannelInterceptor(patterns = "flow1QueueChannel")
	public static class TestChannelInterceptor extends ChannelInterceptorAdapter {

		private final AtomicInteger invoked = new AtomicInteger();

		@Override
		public Message<?> preSend(Message<?> message, MessageChannel channel) {
			this.invoked.incrementAndGet();
			return message;
		}

		public Integer getInvoked() {
			return invoked.get();
		}

	}

	private static class TestPojo {

		private String name;

		private Date date;

		private TestPojo(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Date getDate() {
			return date;
		}

		public void setDate(Date date) {
			this.date = date;
		}

	}

	private static class TestSplitterPojo {

		final List<String> first;

		final List<String> second;

		private TestSplitterPojo(List<String> first, List<String> second) {
			this.first = first;
			this.second = second;
		}

		public List<String> getFirst() {
			return first;
		}

		public List<String> getSecond() {
			return second;
		}

		public List<List<String>> buildList() {
			return Arrays.asList(this.first, this.second);
		}

	}

}

