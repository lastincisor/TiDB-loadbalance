/*
 * Copyright 2020 TiDB Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tidb.jdbc;

import static com.tidb.jdbc.ExceptionHelper.call;
import static com.tidb.jdbc.ExceptionHelper.stringify;
import static com.tidb.jdbc.ExceptionHelper.uncheckedCall;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.mysql.cj.conf.ConnectionUrl;
import com.tidb.jdbc.impl.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

public class LoadBalancingDriver implements Driver {
  private static final Logger logger = Logger.getLogger(LoadBalancingDriver.class.getName());

  private static final String MYSQL_URL_PREFIX = "jdbc:mysql://";
  /** The value is set by {@link System#setProperty(String, String)} */
  private static final String TIDB_URL_MAPPER = "tidb.jdbc.url-mapper";
  /** The value is set by {@link System#setProperty(String, String)} */
  private static final String URL_PROVIDER = "url.provider";
  /** The value is set by {@link System#setProperty(String, String)} */
  private static final String TIDB_MIN_DISCOVER_INTERVAL_KEY = "tidb.jdbc.min-discovery-interval";

  private static final long TIDB_MIN_DISCOVER_INTERVAL = 10000;
  /** The value is set by {@link System#setProperty(String, String)} */
  private static final String TIDB_MAX_DISCOVER_INTERVAL_KEY = "tidb.jdbc.max-discovery-interval";

  private static final long TIDB_MAX_DISCOVER_INTERVAL = 3600000;
  private static final String MYSQL_DRIVER_NAME;
  private static final String NEW_MYSQL_DRIVER_NAME = "com.mysql.cj.jdbc.Driver";
  private static final String OLD_MYSQL_DRIVER_NAME = "com.mysql.jdbc.Driver";

  private static final String WEIGHT_MAPPER = "weight";

  private static final String MYSQL_URL_PREFIX_REGEX = "jdbc:mysql://[^/]+:\\d+";

  private static final Base64.Encoder base64Encoder = Base64.getEncoder();
  private static final AtomicInteger threadId = new AtomicInteger();
  private static final ThreadLocal<MessageDigest> digestThreadLocal =
      ThreadLocal.withInitial(() -> uncheckedCall(() -> MessageDigest.getInstance("md5")));

  private static final Map<String, String> propertiesMap = new HashMap<>();

  static {
    MYSQL_DRIVER_NAME = determineDriverName();

    propertiesMap.put("initialTimeout", "1");
  }

  private final Driver driver;
  private final String wrapperUrlPrefix;

  private static String globalProvider;

  private final long minReloadInterval;
  private final ConcurrentHashMap<String, Discoverer> discoverers = new ConcurrentHashMap<>();
  private final ScheduledThreadPoolExecutor executor;
  /** implements {@link java.util.function.Function}, Default: {@link RandomShuffleUrlMapper} */
  private Function<Bankend, String[]> urlMapper;

  private final DiscovererFactory discovererFactory;

  private Properties properties;

  private Map<String,Integer> weightBankend = new HashMap<>();

  public LoadBalancingDriver(final String wrapperUrlPrefix) {
    this(wrapperUrlPrefix, createUrlMapper(), createDriver(), DiscovererImpl::new);
  }

  LoadBalancingDriver(
      final String wrapperUrlPrefix,
      final Function<Bankend, String[]> mapper,
      final Driver driver,
      final DiscovererFactory discovererFactory) {
    this.wrapperUrlPrefix = requireNonNull(wrapperUrlPrefix, "wrapperUrlPrefix can not be null");
    this.urlMapper = mapper;
    this.driver = driver;
    this.discovererFactory = discovererFactory;
    this.minReloadInterval =
        getLongProperty(
            TIDB_MIN_DISCOVER_INTERVAL_KEY,
            TIDB_MIN_DISCOVER_INTERVAL,
            TIDB_MIN_DISCOVER_INTERVAL,
            TIDB_MAX_DISCOVER_INTERVAL);
    final long maxReloadInterval =
        getLongProperty(
            TIDB_MAX_DISCOVER_INTERVAL_KEY,
            TIDB_MAX_DISCOVER_INTERVAL,
            TIDB_MIN_DISCOVER_INTERVAL,
            TIDB_MAX_DISCOVER_INTERVAL);
    this.executor =
        new ScheduledThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            (runnable) -> {
              Thread newThread = new Thread(runnable);
              newThread.setName("TiDB JDBC Driver Executor Thread - " + threadId.getAndIncrement());
              newThread.setDaemon(true);
              return newThread;
            });
    this.executor.setKeepAliveTime(minReloadInterval * 2, TimeUnit.MILLISECONDS);
    this.executor.allowCoreThreadTimeOut(true);
    this.executor.scheduleWithFixedDelay(
        this::reloadAll, 0, maxReloadInterval, TimeUnit.MILLISECONDS);
  }

  private static String determineDriverName() {
    try {
      Class.forName(NEW_MYSQL_DRIVER_NAME);
      return NEW_MYSQL_DRIVER_NAME;
    } catch (ClassNotFoundException e) {
      return OLD_MYSQL_DRIVER_NAME;
    }
  }

  @SuppressWarnings("unchecked")
  static Function<Bankend, String[]> createUrlMapper(String type) {
    if (type.equalsIgnoreCase("roundrobin")) {
      type = RoundRobinUrlMapper.class.getName();
    } else if (type.equalsIgnoreCase("random")) {
      type = RandomShuffleUrlMapper.class.getName();
    } else if (type.equalsIgnoreCase("weight")){
      type = WeightRandomShuffleUrlMapper.class.getName();
    }

    final String finalType = type;
    return uncheckedCall(
        () ->
            (Function<Bankend, String[]>)
                Class.forName(finalType).getDeclaredConstructor().newInstance());
  }

  private static Function<Bankend, String[]> createUrlMapper() {
    final String provider =
        Optional.ofNullable(System.getProperty(TIDB_URL_MAPPER))
            .orElseGet(
                () ->
                    Optional.ofNullable(System.getProperty(URL_PROVIDER))
                        .orElseGet(RoundRobinUrlMapper.class::getName));
    globalProvider = provider;
    return createUrlMapper(provider);
  }

  private Function<Bankend, String[]> createUrlsMapper() {
    final String provider =
            Optional.ofNullable(properties.getProperty(TIDB_URL_MAPPER))
                    .orElseGet(
                            () ->
                                    Optional.ofNullable(properties.getProperty(URL_PROVIDER))
                                            .orElseGet(RoundRobinUrlMapper.class::getName));
    if(this.urlMapper != null && globalProvider.equals(provider)){
        return null;
    }
    globalProvider = provider;
    return createUrlMapper(provider);
  }

  private static long getLongProperty(
      final String key, final long dft, final long min, final long max) {
    final String value = System.getProperty(key);
    long finalValue = dft;
    if (value != null) {
      finalValue = Long.parseLong(value);
    }
    if (finalValue < min) {
      finalValue = min;
    } else if (finalValue > max) {
      finalValue = max;
    }
    return finalValue;
  }

  private static Driver createDriver() {
    return uncheckedCall(
        () -> (Driver) Class.forName(MYSQL_DRIVER_NAME).getDeclaredConstructor().newInstance());
  }

  public static String getMySqlDriverName() {
    return MYSQL_DRIVER_NAME;
  }

  private void reloadAll() {
    for (final Discoverer discoverer : discoverers.values()) {
      call(discoverer::reload);
    }
  }

  private Connection connect(final Discoverer discoverer, final Properties info)
      throws SQLException {
    String[] backends = null;
    if (System.currentTimeMillis() - discoverer.getLastReloadTime() > minReloadInterval) {
      backends = discoverer.getAndReload();
    } else {
      backends = discoverer.get();
    }
    Bankend bankend = new Bankend();
    bankend.setBankend(backends);
    if(weightBankend.size() > 0){
      bankend.setWeightBankend(weightBankend);
    }
    for (final String url : urlMapper.apply(bankend)) {
      logger.fine(() -> "Try connecting to " + url);
      final ExceptionHelper<Connection> connection = call(() -> driver.connect(url, info));
      if (connection.isOk()) {
        discoverer.succeeded(url);
        return connection.unwrap();
      } else {
        discoverer.failed(url);
        logger.fine(
            () ->
                String.format(
                    "Failed to connect to %s. %s", url, stringify(connection.unwrapErr())));
      }
    }
    throw new SQLException("can not get connection");
  }

  @Override
  public Connection connect(final String tidbUrl, final Properties info) throws SQLException {
    String mysqlUrl = getMySqlUrl(tidbUrl);
    Function<Bankend, String[]> mapper = createUrlsMapper();
    if(mapper != null){
      this.urlMapper = mapper;
    }
    return connect(checkAndCreateDiscoverer(mysqlUrl, info), info);
  }

  private String signature(final String tidbUrl, final Properties info) {
    final MessageDigest digest = digestThreadLocal.get();
    digest.reset();
    digest.update(tidbUrl.getBytes(StandardCharsets.UTF_8));
    if (info != null && !info.isEmpty()) {
      String[] keys = info.keySet().stream().map(Object::toString).toArray(String[]::new);
      Arrays.sort(keys);
      for (final String key : keys) {
        digest.update(key.getBytes(StandardCharsets.UTF_8));
        digest.update(info.get(key).toString().getBytes(StandardCharsets.UTF_8));
      }
    }
    return base64Encoder.encodeToString(digest.digest());
  }

  private Discoverer checkAndCreateDiscoverer(final String tidbUrl, final Properties info) {
    return discoverers.computeIfAbsent(
        signature(tidbUrl, info), (k) -> discovererFactory.create(driver, tidbUrl, info, executor));
  }

  @Override
  public boolean acceptsURL(final String url) throws SQLException {
    return driver.acceptsURL(getMySqlUrl(url));
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(final String url, final Properties info)
      throws SQLException {
    return driver.getPropertyInfo(getMySqlUrl(url), info);
  }

  @Override
  public int getMajorVersion() {
    return driver.getMajorVersion();
  }

  @Override
  public int getMinorVersion() {
    return driver.getMinorVersion();
  }

  @Override
  public boolean jdbcCompliant() {
    return driver.jdbcCompliant();
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return driver.getParentLogger();
  }

  private String getMySqlUrl(final String tidbUrl) {
    String mysqlUrl = defaultProperties(tidbUrl.replaceFirst(wrapperUrlPrefix, MYSQL_URL_PREFIX));
    mysqlUrl = parserProperties(mysqlUrl);
    return mysqlUrl;
  }

  private String parserProperties(String tidbUrl){
    if(!ConnectionUrl.acceptsUrl(tidbUrl)){
      return tidbUrl;
    }
    Properties info = new Properties();
    ConnectionUrl connStr = ConnectionUrl.getConnectionUrlInstance(tidbUrl, info);
    this.properties = connStr.getConnectionArgumentsAsProperties();
    if(properties.getProperty(TIDB_URL_MAPPER) == null){
      return tidbUrl;
    }
    if(!WEIGHT_MAPPER.equals(properties.getProperty(TIDB_URL_MAPPER))){
      return tidbUrl;
    }
    AtomicReference<String> mysqlUrl = new AtomicReference<>("");

    connStr.getHostsList().forEach(hostInfo -> {
      String host = hostInfo.getHost();
      String url = tidbUrl.replaceFirst(
              MYSQL_URL_PREFIX_REGEX, format("jdbc:mysql://%s:%s", host.split(":")[0], host.split(":")[1]));
      weightBankend.put(url,hostInfo.getPort());
      mysqlUrl.set(url);
    });
    return mysqlUrl.get();
  }

  private String defaultProperties(String tidbUrl) {
    if (tidbUrl == null) {
      return null;
    }
    StringJoiner prop = new StringJoiner("&");
    propertiesMap.forEach(
        (k, v) -> {
          if (!tidbUrl.contains(k)) {
            prop.add(k + "=" + v);
          }
        });
    if (tidbUrl.contains("?")) {
      return tidbUrl + "&" + prop;
    } else {
      return tidbUrl + "?" + prop;
    }
  }

  public void deregister() throws SQLException {
    try {
      java.sql.DriverManager.deregisterDriver(this);
    } finally {
      executor.shutdown();
    }
  }
}
