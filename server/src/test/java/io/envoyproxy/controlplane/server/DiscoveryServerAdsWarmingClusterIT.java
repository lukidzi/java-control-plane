package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.TestSnapshots.createSnapshot;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import io.envoyproxy.controlplane.cache.CacheStatusInfo;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.cache.SnapshotCache;
import io.envoyproxy.controlplane.cache.StatusInfo;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.WatchCancelledException;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.Http2ProtocolOptions;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;
import org.testcontainers.shaded.org.apache.commons.lang.math.RandomUtils;

public class DiscoveryServerAdsWarmingClusterIT {

  private static final String CONFIG = "envoy/ads.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;
  private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamResponseLatch = new CountDownLatch(1);

  private static final NettyGrpcServerRule ADS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      final DiscoveryServerCallbacks callbacks = new DiscoveryServerCallbacks() {
        @Override
        public void onStreamOpen(long streamId, String typeUrl) {
          onStreamOpenLatch.countDown();
        }

        @Override
        public void onStreamRequest(long streamId, DiscoveryRequest request) {
          onStreamRequestLatch.countDown();
        }

        @Override
        public void onStreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
          if (request.getTypeUrl().equals(Resources.CLUSTER_TYPE_URL)) {
            executorService.submit(() -> cache.setSnapshot(
                GROUP,
                createSnapshot(true,
                    "upstream",
                    UPSTREAM.ipAddress(),
                    EchoContainer.PORT,
                    "listener0",
                    LISTENER_PORT,
                    "route0",
                    "2"))
            );
          }
          onStreamResponseLatch.countDown();
        }
      };

      cache.setSnapshot(
          GROUP,
          createSnapshotWithNotWorkingCluster(true,
              "upstream",
              UPSTREAM.ipAddress(),
              EchoContainer.PORT,
              "listener0",
              LISTENER_PORT,
              "route0"));

      DiscoveryServer server = new DiscoveryServer(callbacks, cache);

      builder.addService(server.getAggregatedDiscoveryServiceImpl());
    }
  };

  private static final Network NETWORK = Network.newNetwork();

  private static final EnvoyContainer ENVOY = new EnvoyContainer(CONFIG, () -> ADS.getServer().getPort())
      .withExposedPorts(LISTENER_PORT)
      .withNetwork(NETWORK);

  private static final EchoContainer UPSTREAM = new EchoContainer()
      .withNetwork(NETWORK)
      .withNetworkAliases("upstream");

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(UPSTREAM)
      .around(ADS)
      .around(ENVOY);

  @Test
  public void validateTestRequestToEchoServerViaEnvoy() throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to open ADS stream");

    assertThat(onStreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to receive ADS request");

    assertThat(onStreamResponseLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to send ADS response");

    String baseUri = String.format("http://%s:%d", ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(LISTENER_PORT));

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseUri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));
  }

  static Snapshot createSnapshotWithNotWorkingCluster(
      boolean ads,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName) {

    ConfigSource edsSource = ConfigSource.newBuilder()
        .setAds(AggregatedConfigSource.getDefaultInstance())
        .build();

    Cluster cluster = Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(RandomUtils.nextInt(5)))
        // we are enabling HTTP2 - communication with cluster won't work
        .setHttp2ProtocolOptions(Http2ProtocolOptions.newBuilder().build())
        .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
            .setEdsConfig(edsSource)
            .setServiceName(clusterName))
        .setType(Cluster.DiscoveryType.EDS)
        .build();
    ClusterLoadAssignment endpoint = TestResources.createEndpoint(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListener(ads, listenerName, listenerPort, routeName);
    RouteConfiguration route = TestResources.createRoute(routeName, clusterName);

    // here we have new version of resources other than CDS.
    return Snapshot.create(
        ImmutableList.of(cluster),
        "1",
        ImmutableList.of(endpoint),
        "2",
        ImmutableList.of(listener),
        "2",
        ImmutableList.of(route),
        "2",
        ImmutableList.of(),
        "2");
  }


  /**
   * Code has been copied from io.envoyproxy.controlplane.cache.SimpleCache to show specific case when
   * Envoy might stuck with warming cluster. Class has removed lines from method setSnapshot which are responsible for
   * responding for watches. Because to reproduce this problem we need a lot of connected Envoy's and changes to
   * snapshot it is easier to reproduce this way.
   */
  static class SimpleCache<T> implements SnapshotCache<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(io.envoyproxy.controlplane.cache.SimpleCache.class);

    private final NodeGroup<T> groups;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    @GuardedBy("lock")
    private final Map<T, Snapshot> snapshots = new HashMap<>();
    private final ConcurrentMap<T, CacheStatusInfo<T>> statuses = new ConcurrentHashMap<>();

    private AtomicLong watchCount = new AtomicLong();

    /**
     * Constructs a simple cache.
     *
     * @param groups maps an envoy host to a node group
     */
    public SimpleCache(NodeGroup<T> groups) {
      this.groups = groups;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean clearSnapshot(T group) {
      // we take a writeLock to prevent watches from being created
      writeLock.lock();
      try {
        CacheStatusInfo<T> status = statuses.get(group);

        // If we don't know about this group, do nothing.
        if (status != null && status.numWatches() > 0) {
          LOGGER.warn("tried to clear snapshot for group with existing watches, group={}", group);

          return false;
        }

        statuses.remove(group);
        snapshots.remove(group);

        return true;
      } finally {
        writeLock.unlock();
      }
    }

    @Override
    public Watch createWatch(
        boolean ads,
        DiscoveryRequest request,
        Set<String> knownResourceNames,
        Consumer<Response> responseConsumer) {
      return createWatch(ads, request, knownResourceNames, responseConsumer, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Watch createWatch(
        boolean ads,
        DiscoveryRequest request,
        Set<String> knownResourceNames,
        Consumer<Response> responseConsumer,
        boolean hasClusterChanged) {

      T group = groups.hash(request.getNode());
      // even though we're modifying, we take a readLock to allow multiple watches to be created in parallel since it
      // doesn't conflict
      readLock.lock();
      try {
        CacheStatusInfo<T> status = statuses.computeIfAbsent(group, g -> new CacheStatusInfo<>(group));
        status.setLastWatchRequestTime(System.currentTimeMillis());

        Snapshot snapshot = snapshots.get(group);
        String version = snapshot == null ? "" : snapshot.version(request.getTypeUrl(), request.getResourceNamesList());

        Watch watch = new Watch(ads, request, responseConsumer);

        if (snapshot != null) {
          Set<String> requestedResources = ImmutableSet.copyOf(request.getResourceNamesList());

          // If the request is asking for resources we haven't sent to the proxy yet,
          //see if we have additional resources.
          if (!knownResourceNames.equals(requestedResources)) {
            Sets.SetView<String> newResourceHints = Sets.difference(requestedResources, knownResourceNames);

            // If any of the newly requested resources are in the snapshot respond immediately.
            //If not we'll fall back to version comparisons.
            if (snapshot.resources(request.getTypeUrl())
                .keySet()
                .stream()
                .anyMatch(newResourceHints::contains)) {
              respond(watch, snapshot, group);

              return watch;
            }
          } else if (hasClusterChanged && request.getTypeUrl().equals(Resources.ENDPOINT_TYPE_URL)) {
            respond(watch, snapshot, group);

            return watch;
          }
        }

        // If the requested version is up-to-date or missing a response, leave an open watch.
        if (snapshot == null || request.getVersionInfo().equals(version)) {
          long watchId = watchCount.incrementAndGet();

          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("open watch {} for {}[{}] from node {} for version {}",
                watchId,
                request.getTypeUrl(),
                String.join(", ", request.getResourceNamesList()),
                group,
                request.getVersionInfo());
          }

          status.setWatch(watchId, watch);

          watch.setStop(() -> status.removeWatch(watchId));

          return watch;
        }

        // Otherwise, the watch may be responded immediately
        boolean responded = respond(watch, snapshot, group);

        if (!responded) {
          long watchId = watchCount.incrementAndGet();

          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("did not respond immediately, leaving open watch {} for {}[{}] from node {} for version {}",
                watchId,
                request.getTypeUrl(),
                String.join(", ", request.getResourceNamesList()),
                group,
                request.getVersionInfo());
          }

          status.setWatch(watchId, watch);

          watch.setStop(() -> status.removeWatch(watchId));
        }

        return watch;
      } finally {
        readLock.unlock();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snapshot getSnapshot(T group) {
      readLock.lock();

      try {
        return snapshots.get(group);
      } finally {
        readLock.unlock();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<T> groups() {
      return ImmutableSet.copyOf(statuses.keySet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setSnapshot(T group, Snapshot snapshot) {
      // we take a writeLock to prevent watches from being created while we update the snapshot
      CacheStatusInfo<T> status;
      writeLock.lock();
      try {
        // Update the existing snapshot entry.
        snapshots.put(group, snapshot);
        status = statuses.get(group);
      } finally {
        writeLock.unlock();
      }

      if (status == null) {
        return;
      }
      // This code has been removed to show specific case which is hard to reproduce in integration test:
      // 1. Envoy connects to control-plane
      // 2. Snapshot is in cache but there is no watches
      // 3. Envoy sends CDS request
      // 4. Control-plane respond with CDS in createWatch method
      // 5. There is snapshot update which change CDS and EDS versions
      // 6. Envoy sends EDS request
      // 7. Control respond with EDS in createWatch method
      // 8. Envoy resume CDS and EDS requests and request CDS
      // 9. Control plane respond with CDS in createWatch method
      // 10. Envoy sends EDS requests
      // 11. Control plane doesn't respond because version hasn't changed
      // 12. Cluster of service stays in warming phase
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StatusInfo statusInfo(T group) {
      readLock.lock();

      try {
        return statuses.get(group);
      } finally {
        readLock.unlock();
      }
    }

    private Response createResponse(DiscoveryRequest request,
                                    Map<String, ? extends Message> resources,
                                    String version) {
      Collection<? extends Message> filtered = request.getResourceNamesList().isEmpty()
          ? resources.values()
          : request.getResourceNamesList().stream()
          .map(resources::get)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());

      return Response.create(request, filtered, version);
    }

    private boolean respond(Watch watch, Snapshot snapshot, T group) {
      Map<String, ? extends Message> snapshotResources = snapshot.resources(watch.request().getTypeUrl());

      if (!watch.request().getResourceNamesList().isEmpty() && watch.ads()) {
        Collection<String> missingNames = watch.request().getResourceNamesList().stream()
            .filter(name -> !snapshotResources.containsKey(name))
            .collect(Collectors.toList());

        if (!missingNames.isEmpty()) {
          LOGGER.info(
              "not responding in ADS mode for {} from node {} at version {} for request [{}] since [{}] not in "
                  + "snapshot",
              watch.request().getTypeUrl(),
              group,
              snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList()),
              String.join(", ", watch.request().getResourceNamesList()),
              String.join(", ", missingNames));

          return false;
        }
      }

      String version = snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList());

      LOGGER.info("responding for {} from node {} at version {} with version {}",
          watch.request().getTypeUrl(),
          group,
          watch.request().getVersionInfo(),
          version);

      Response response = createResponse(
          watch.request(),
          snapshotResources,
          version);

      try {
        watch.respond(response);
        return true;
      } catch (WatchCancelledException e) {
        LOGGER.error(
            "failed to respond for {} from node {} at version {} with version {} because watch was already cancelled",
            watch.request().getTypeUrl(),
            group,
            watch.request().getVersionInfo(),
            version);
      }

      return false;
    }
  }
}
