package net.corda.client.rpc

import net.corda.client.rpc.internal.RPCClient
import net.corda.client.rpc.internal.RPCClientConfiguration
import net.corda.core.context.Trace
import net.corda.core.crypto.random63BitValue
import net.corda.core.internal.concurrent.fork
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.RPCOps
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.serialize
import net.corda.core.utilities.*
import net.corda.node.services.messaging.RPCServerConfiguration
import net.corda.nodeapi.RPCApi
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.internal.testThreadFactory
import net.corda.testing.node.User
import net.corda.testing.node.internal.*
import org.apache.activemq.artemis.api.core.SimpleString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import rx.Observable
import rx.subjects.PublishSubject
import rx.subjects.UnicastSubject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertNotNull

class RPCStabilityTests {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule(true)
    private val pool = Executors.newFixedThreadPool(10, testThreadFactory())
    @After
    fun shutdown() {
        pool.shutdown()
    }

    object DummyOps : RPCOps {
        override val protocolVersion = 0
    }

    private fun waitUntilNumberOfThreadsStable(executorService: ScheduledExecutorService): Int {
        val values = ConcurrentLinkedQueue<Int>()
        return poll(executorService, "number of threads to become stable", 250.millis) {
            values.add(Thread.activeCount())
            if (values.size > 5) {
                values.poll()
            }
            val first = values.peek()
            if (values.size == 5 && values.all { it == first }) {
                first
            } else {
                null
            }
        }.get()
    }

    @Test
    fun `client and server dont leak threads`() {
        val executor = Executors.newScheduledThreadPool(1)
        fun startAndStop() {
            rpcDriver {
                val server = startRpcServer<RPCOps>(ops = DummyOps).get()
                startRpcClient<RPCOps>(server.broker.hostAndPort!!).get()
            }
        }
        repeat(5) {
            startAndStop()
        }
        val numberOfThreadsBefore = waitUntilNumberOfThreadsStable(executor)
        repeat(5) {
            startAndStop()
        }
        val numberOfThreadsAfter = waitUntilNumberOfThreadsStable(executor)
        // This is a less than check because threads from other tests may be shutting down while this test is running.
        // This is therefore a "best effort" check. When this test is run on its own this should be a strict equality.
        assertTrue(numberOfThreadsBefore >= numberOfThreadsAfter)
        executor.shutdownNow()
    }

    @Test
    fun `client doesnt leak threads when it fails to start`() {
        val executor = Executors.newScheduledThreadPool(1)
        fun startAndStop() {
            rpcDriver {
                Try.on { startRpcClient<RPCOps>(NetworkHostAndPort("localhost", 9999)).get() }
                val server = startRpcServer<RPCOps>(ops = DummyOps)
                Try.on {
                    startRpcClient<RPCOps>(
                            server.get().broker.hostAndPort!!,
                            configuration = RPCClientConfiguration.default.copy(minimumServerProtocolVersion = 1)
                    ).get()
                }
            }
        }
        repeat(5) {
            startAndStop()
        }
        val numberOfThreadsBefore = waitUntilNumberOfThreadsStable(executor)
        repeat(5) {
            startAndStop()
        }
        val numberOfThreadsAfter = waitUntilNumberOfThreadsStable(executor)

        assertTrue(numberOfThreadsBefore >= numberOfThreadsAfter)
        executor.shutdownNow()
    }

    fun RpcBrokerHandle.getStats(): Map<String, Any> {
        return serverControl.run {
            mapOf(
                    "connections" to listConnectionIDs().toSet(),
                    "sessionCount" to listConnectionIDs().flatMap { listSessions(it).toList() }.size,
                    "consumerCount" to totalConsumerCount
            )
        }
    }

    @Test
    fun `rpc server close doesnt leak broker resources`() {
        rpcDriver {
            fun startAndCloseServer(broker: RpcBrokerHandle) {
                startRpcServerWithBrokerRunning(
                        configuration = RPCServerConfiguration.default,
                        ops = DummyOps,
                        brokerHandle = broker
                ).rpcServer.close()
            }

            val broker = startRpcBroker().get()
            startAndCloseServer(broker)
            val initial = broker.getStats()
            repeat(100) {
                startAndCloseServer(broker)
            }
            pollUntilTrue("broker resources to be released") {
                initial == broker.getStats()
            }
        }
    }

    @Test
    fun `rpc client close doesnt leak broker resources`() {
        rpcDriver {
            val server = startRpcServer(configuration = RPCServerConfiguration.default, ops = DummyOps).get()
            RPCClient<RPCOps>(server.broker.hostAndPort!!).start(RPCOps::class.java, rpcTestUser.username, rpcTestUser.password).close()
            val initial = server.broker.getStats()
            repeat(100) {
                val connection = RPCClient<RPCOps>(server.broker.hostAndPort!!).start(RPCOps::class.java, rpcTestUser.username, rpcTestUser.password)
                connection.close()
            }
            pollUntilTrue("broker resources to be released") {
                initial == server.broker.getStats()
            }
        }
    }

    @Test
    fun `rpc server close is idempotent`() {
        rpcDriver {
            val server = startRpcServer(ops = DummyOps).get()
            repeat(10) {
                server.rpcServer.close()
            }
        }
    }

    @Test
    fun `rpc client close is idempotent`() {
        rpcDriver {
            val serverShutdown = shutdownManager.follower()
            val server = startRpcServer(ops = DummyOps).get()
            serverShutdown.unfollow()
            // With the server up
            val connection1 = RPCClient<RPCOps>(server.broker.hostAndPort!!).start(RPCOps::class.java, rpcTestUser.username, rpcTestUser.password)
            repeat(10) {
                connection1.close()
            }
            val connection2 = RPCClient<RPCOps>(server.broker.hostAndPort!!).start(RPCOps::class.java, rpcTestUser.username, rpcTestUser.password)
            serverShutdown.shutdown()
            // With the server down
            repeat(10) {
                connection2.close()
            }
        }
    }

    interface LeakObservableOps : RPCOps {
        fun leakObservable(): Observable<Nothing>
    }

    @Test
    fun `client cleans up leaked observables`() {
        rpcDriver {
            val leakObservableOpsImpl = object : LeakObservableOps {
                val leakedUnsubscribedCount = AtomicInteger(0)
                override val protocolVersion = 0
                override fun leakObservable(): Observable<Nothing> {
                    return PublishSubject.create<Nothing>().doOnUnsubscribe {
                        leakedUnsubscribedCount.incrementAndGet()
                    }
                }
            }
            val server = startRpcServer<LeakObservableOps>(ops = leakObservableOpsImpl)
            val proxy = startRpcClient<LeakObservableOps>(server.get().broker.hostAndPort!!).get()
            // Leak many observables
            val N = 200
            (1..N).map {
                pool.fork { proxy.leakObservable(); Unit }
            }.transpose().getOrThrow()
            // In a loop force GC and check whether the server is notified
            while (true) {
                System.gc()
                if (leakObservableOpsImpl.leakedUnsubscribedCount.get() == N) break
                Thread.sleep(100)
            }
        }
    }

    interface ReconnectOps : RPCOps {
        fun ping(): String
    }

    @Test
    fun `client reconnects to rebooted server`() {
        rpcDriver {
            val ops = object : ReconnectOps {
                override val protocolVersion = 0
                override fun ping() = "pong"
            }

            val serverFollower = shutdownManager.follower()
            val serverPort = startRpcServer<ReconnectOps>(ops = ops).getOrThrow().broker.hostAndPort!!
            serverFollower.unfollow()
            // Set retry interval to 1s to reduce test duration
            val clientConfiguration = RPCClientConfiguration.default.copy(connectionRetryInterval = 1.seconds)
            val clientFollower = shutdownManager.follower()
            val client = startRpcClient<ReconnectOps>(serverPort, configuration = clientConfiguration).getOrThrow()
            clientFollower.unfollow()
            assertEquals("pong", client.ping())
            serverFollower.shutdown()
            startRpcServer<ReconnectOps>(ops = ops, customPort = serverPort).getOrThrow()
            val pingFuture = pool.fork(client::ping)
            assertEquals("pong", pingFuture.getOrThrow(10.seconds))
            clientFollower.shutdown() // Driver would do this after the new server, causing hang.
        }
    }

    @Test
    fun `client reconnects to server and resends buffered messages`() {
        rpcDriver(startNodesInProcess = false) {
            var nodeInfo: NodeInfo? = null
            var nodeTime: Instant?  = null
            val alice = startNode(providedName = ALICE_NAME,
                    rpcUsers = listOf(User("alice", "alice", setOf("ALL")))).getOrThrow()
            CordaRPCClient(alice.rpcAddress).use("alice", "alice") { connection ->
                val proxy = connection.proxy
                alice.stop()
                val nodeInfoThread = thread {
                    nodeInfo = proxy.nodeInfo()
                }

                val currentTimeThread = thread {
                    nodeTime = proxy.currentNodeTime()
                }

                Thread.sleep(5000)
                startNode(providedName = ALICE_NAME,
                        rpcUsers = listOf(User("alice", "alice", setOf("ALL"))),
                        customOverrides = mapOf("rpcSettings" to mapOf("address" to "localhost:${alice.rpcAddress.port}")))
                currentTimeThread.join()
                nodeInfoThread.join()
                assertNotNull(nodeInfo)
                assertNotNull(nodeTime)
            }
        }
    }

    @Test
    fun `connection failover fails, rpc calls throw`() {
        rpcDriver {
            val ops = object : ReconnectOps {
                override val protocolVersion = 0
                override fun ping() = "pong"
            }

            val serverFollower = shutdownManager.follower()
            val serverPort = startRpcServer<ReconnectOps>(ops = ops).getOrThrow().broker.hostAndPort!!
            serverFollower.unfollow()
            // Set retry interval to 1s to reduce test duration
            val clientConfiguration = RPCClientConfiguration.default.copy(connectionRetryInterval = 1.seconds, maxReconnectAttempts = 5)
            val clientFollower = shutdownManager.follower()
            val client = startRpcClient<ReconnectOps>(serverPort, configuration = clientConfiguration).getOrThrow()
            clientFollower.unfollow()
            assertEquals("pong", client.ping())
            serverFollower.shutdown()
            try {
                client.ping()
            } catch (e: Exception) {
                assertTrue(e is RPCException)
            }
            clientFollower.shutdown() // Driver would do this after the new server, causing hang.
        }
    }

    interface ThreadOps : RPCOps {
        fun sendMessage(id: Int, msgNo: Int): String
    }

    @Test
    fun `multiple threads with 1000 messages for each thread`() {
        val messageNo = 1000
        val threadNo = 8
        val ops = object : ThreadOps {
            override val protocolVersion = 0
            override fun sendMessage(id: Int, msgNo: Int): String {
                return "($id-$msgNo)"
            }
        }

        rpcDriver(startNodesInProcess = false) {
            val serverFollower = shutdownManager.follower()
            val serverPort = startRpcServer<ThreadOps>(rpcUser = User("alice", "alice", setOf("ALL")),
                    ops = ops).getOrThrow().broker.hostAndPort!!

            serverFollower.unfollow()
            val proxy = RPCClient<ThreadOps>(serverPort).start(ThreadOps::class.java, "alice", "alice").proxy
            val expectedMap = mutableMapOf<Int, StringBuilder>()
            val resultsMap = mutableMapOf<Int, StringBuilder>()

            (1 until threadNo).forEach { nr ->
                (1 until messageNo).forEach { msgNo ->
                    expectedMap[nr] = expectedMap.getOrDefault(nr, StringBuilder()).append("($nr-$msgNo)")
                }
            }

            val threads = mutableMapOf<Int, Thread>()
            (1 until threadNo).forEach { nr ->
                val thread = thread {
                    (1 until messageNo).forEach { msgNo ->
                        resultsMap[nr] = resultsMap.getOrDefault(nr, StringBuilder()).append(proxy.sendMessage(nr, msgNo))
                    }
                }
                threads[nr] = thread
            }
            // give the threads a chance to start sending some messages
            Thread.sleep(50)
            serverFollower.shutdown()
            startRpcServer<ThreadOps>(rpcUser = User("alice", "alice", setOf("ALL")),
                    ops = ops, customPort = serverPort).getOrThrow()
            threads.values.forEach { it.join() }
            (1 until threadNo).forEach { assertEquals(expectedMap[it].toString(), resultsMap[it].toString()) }
        }

    }

    interface TrackSubscriberOps : RPCOps {
        fun subscribe(): Observable<Unit>
    }

    /**
     * In this test we create a number of out of process RPC clients that call [TrackSubscriberOps.subscribe] in a loop.
     */
    @Test
    fun `server cleans up queues after disconnected clients`() {
        rpcDriver {
            val trackSubscriberOpsImpl = object : TrackSubscriberOps {
                override val protocolVersion = 0
                val subscriberCount = AtomicInteger(0)
                val trackSubscriberCountObservable = UnicastSubject.create<Unit>().share().
                        doOnSubscribe { subscriberCount.incrementAndGet() }.
                        doOnUnsubscribe { subscriberCount.decrementAndGet() }

                override fun subscribe(): Observable<Unit> {
                    return trackSubscriberCountObservable
                }
            }
            val server = startRpcServer<TrackSubscriberOps>(
                    configuration = RPCServerConfiguration.default.copy(
                            reapInterval = 100.millis
                    ),
                    ops = trackSubscriberOpsImpl
            ).get()

            val numberOfClients = 4
            val clients = (1..numberOfClients).map {
                startRandomRpcClient<TrackSubscriberOps>(server.broker.hostAndPort!!)
            }.transpose().get()

            // Poll until all clients connect
            pollUntilClientNumber(server, numberOfClients)
            pollUntilTrue("number of times subscribe() has been called") { trackSubscriberOpsImpl.subscriberCount.get() >= 100 }.get()
            // Kill one client
            clients[0].destroyForcibly()
            pollUntilClientNumber(server, numberOfClients - 1)
            // Kill the rest
            (1..numberOfClients - 1).forEach {
                clients[it].destroyForcibly()
            }
            pollUntilClientNumber(server, 0)
            // Now poll until the server detects the disconnects and unsubscribes from all obserables.
            pollUntilTrue("number of times subscribe() has been called") { trackSubscriberOpsImpl.subscriberCount.get() == 0 }.get()
        }
    }

    interface SlowConsumerRPCOps : RPCOps {
        fun streamAtInterval(interval: Duration, size: Int): Observable<ByteArray>
    }

    class SlowConsumerRPCOpsImpl : SlowConsumerRPCOps {
        override val protocolVersion = 0

        override fun streamAtInterval(interval: Duration, size: Int): Observable<ByteArray> {
            val chunk = ByteArray(size)
            return Observable.interval(interval.toMillis(), TimeUnit.MILLISECONDS).map { chunk }
        }
    }

    @Test
    fun `slow consumers are kicked`() {
        rpcDriver {
            val server = startRpcServer(maxBufferedBytesPerClient = 10 * 1024 * 1024, ops = SlowConsumerRPCOpsImpl()).get()

            // Construct an RPC session manually so that we can hang in the message handler
            val myQueue = "${RPCApi.RPC_CLIENT_QUEUE_NAME_PREFIX}.test.${random63BitValue()}"
            val session = startArtemisSession(server.broker.hostAndPort!!)
            session.createTemporaryQueue(myQueue, myQueue)
            val consumer = session.createConsumer(myQueue, null, -1, -1, false)
            consumer.setMessageHandler {
                Thread.sleep(50) // 5x slower than the server producer
                it.acknowledge()
            }
            val producer = session.createProducer(RPCApi.RPC_SERVER_QUEUE_NAME)
            session.start()

            pollUntilClientNumber(server, 1)

            val message = session.createMessage(false)
            val request = RPCApi.ClientToServer.RpcRequest(
                    clientAddress = SimpleString(myQueue),
                    methodName = SlowConsumerRPCOps::streamAtInterval.name,
                    serialisedArguments = listOf(10.millis, 123456).serialize(context = SerializationDefaults.RPC_SERVER_CONTEXT),
                    replyId = Trace.InvocationId.newInstance(),
                    sessionId = Trace.SessionId.newInstance()
            )
            request.writeToClientMessage(message)
            message.putLongProperty(RPCApi.DEDUPLICATION_SEQUENCE_NUMBER_FIELD_NAME, 0)
            producer.send(message)
            session.commit()

            // We are consuming slower than the server is producing, so we should be kicked after a while
            pollUntilClientNumber(server, 0)
        }
    }

    @Test
    fun `deduplication in the server`() {
        rpcDriver {
            val server = startRpcServer(ops = SlowConsumerRPCOpsImpl()).getOrThrow()

            // Construct an RPC client session manually
            val myQueue = "${RPCApi.RPC_CLIENT_QUEUE_NAME_PREFIX}.test.${random63BitValue()}"
            val session = startArtemisSession(server.broker.hostAndPort!!)
            session.createTemporaryQueue(myQueue, myQueue)
            val consumer = session.createConsumer(myQueue, null, -1, -1, false)
            val replies = ArrayList<Any>()
            consumer.setMessageHandler {
                replies.add(it)
                it.acknowledge()
            }

            val producer = session.createProducer(RPCApi.RPC_SERVER_QUEUE_NAME)
            session.start()

            pollUntilClientNumber(server, 1)

            val message = session.createMessage(false)
            val request = RPCApi.ClientToServer.RpcRequest(
                    clientAddress = SimpleString(myQueue),
                    methodName = DummyOps::protocolVersion.name,
                    serialisedArguments = emptyList<Any>().serialize(context = SerializationDefaults.RPC_SERVER_CONTEXT),
                    replyId = Trace.InvocationId.newInstance(),
                    sessionId = Trace.SessionId.newInstance()
            )
            request.writeToClientMessage(message)
            message.putLongProperty(RPCApi.DEDUPLICATION_SEQUENCE_NUMBER_FIELD_NAME, 0)
            producer.send(message)
            // duplicate the message
            producer.send(message)

            pollUntilTrue("Number of replies is 1") {
                replies.size == 1
            }.getOrThrow()
        }
    }

    @Test
    fun `deduplication in the client`() {
        rpcDriver {
            val broker = startRpcBroker().getOrThrow()

            // Construct an RPC server session manually
            val session = startArtemisSession(broker.hostAndPort!!)
            val consumer = session.createConsumer(RPCApi.RPC_SERVER_QUEUE_NAME)
            val producer = session.createProducer()
            val dedupeId = AtomicLong(0)
            consumer.setMessageHandler {
                it.acknowledge()
                val request = RPCApi.ClientToServer.fromClientMessage(it)
                when (request) {
                    is RPCApi.ClientToServer.RpcRequest -> {
                        val reply = RPCApi.ServerToClient.RpcReply(request.replyId, Try.Success(0), "server")
                        val message = session.createMessage(false)
                        reply.writeToClientMessage(SerializationDefaults.RPC_SERVER_CONTEXT, message)
                        message.putLongProperty(RPCApi.DEDUPLICATION_SEQUENCE_NUMBER_FIELD_NAME, dedupeId.getAndIncrement())
                        producer.send(request.clientAddress, message)
                        // duplicate the reply
                        producer.send(request.clientAddress, message)
                    }
                    is RPCApi.ClientToServer.ObservablesClosed -> {
                    }
                }
            }
            session.start()

            startRpcClient<RPCOps>(broker.hostAndPort!!).getOrThrow()
        }
    }
}

fun RPCDriverDSL.pollUntilClientNumber(server: RpcServerHandle, expected: Int) {
    pollUntilTrue("number of RPC clients to become $expected") {
        val clientAddresses = server.broker.serverControl.addressNames.filter { it.startsWith(RPCApi.RPC_CLIENT_QUEUE_NAME_PREFIX) }
        clientAddresses.size == expected
    }.get()
}