/**
 * Copyright (C) 2012 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.gtfs_realtime.alerts_producer_demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.codec.binary.Hex;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeExporterModule;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeProvider;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;

/**
 * This class produces GTFS-realtime alerts by periodically polling the custom
 * SEPTA alerts API and converting the resulting alert data into the
 * GTFS-realtime format.
 * 
 * Since this class implements {@link GtfsRealtimeProvider}, it will
 * automatically be queried by the {@link GtfsRealtimeExporterModule} to export
 * the GTFS-realtime feeds to file or to host them using a simple web-server, as
 * configured by the client.
 * 
 * @author bdferris
 * 
 */
@Singleton
public class GtfsRealtimeProviderImpl implements GtfsRealtimeProvider {

  private static final Logger _log = LoggerFactory.getLogger(GtfsRealtimeProviderImpl.class);

  private ScheduledExecutorService _executor;

  private AlertTextExtractor _alertTextExtractor;

  private volatile FeedMessage _alerts = GtfsRealtimeLibrary.createFeedMessageBuilder().build();

  private URL _url;

  /**
   * How often alerts will be downloaded, in seconds.
   */
  private int _refreshInterval = 30;

  @Inject
  public void setAlertTextExtractor(AlertTextExtractor alertTextExtractor) {
    _alertTextExtractor = alertTextExtractor;
  }

  /**
   * @param url the URL for the SEPTA alerts API.
   */
  public void setUrl(URL url) {
    _url = url;
  }

  /**
   * @param refreshInterval how often alerts will be downloaded, in seconds.
   */
  public void setRefreshInterval(int refreshInterval) {
    _refreshInterval = refreshInterval;
  }

  /**
   * The start method automatically starts up a recurring task that periodically
   * downloads the latest alerts from the SEPTA alerts stream and processes
   * them.
   */
  @PostConstruct
  public void start() {
    _log.info("starting GTFS-realtime service");
    _executor = Executors.newSingleThreadScheduledExecutor();
    _executor.scheduleAtFixedRate(new AlertRefreshTask(), 0, _refreshInterval,
        TimeUnit.SECONDS);
  }

  /**
   * The stop method cancels the recurring alert downloader task.
   */
  @PreDestroy
  public void stop() {
    _log.info("stopping GTFS-realtime service");
    _executor.shutdownNow();
  }

  /****
   * {@link GtfsRealtimeProvider} Interface
   ****/

  /**
   * We don't care about trip updates, so we return an empty feed here.
   */
  @Override
  public FeedMessage getTripUpdates() {
    FeedMessage.Builder feedMessage = GtfsRealtimeLibrary.createFeedMessageBuilder();
    return feedMessage.build();
  }

  /**
   * We don't care about vehicle positions, so we return an empty feed here.
   */
  @Override
  public FeedMessage getVehiclePositions() {
    FeedMessage.Builder feedMessage = GtfsRealtimeLibrary.createFeedMessageBuilder();
    return feedMessage.build();
  }

  /**
   * We DO care about alerts, so we return the most recently generated alerts
   * feed.
   */
  @Override
  public FeedMessage getAlerts() {
    return _alerts;
  }

  /****
   * Private Methods - Here is where the real work happens
   ****/

  /**
   * This method downloads the latest alerts, processes each alert in turn, and
   * create a GTFS-realtime feed of alerts as a result.
   */
  private void refreshAlerts() throws IOException, JSONException {

    /**
     * We download the alerts as an array of JSON objects.
     */
    JSONArray alertArray = downloadAlerts();

    /**
     * The FeedMessage.Builder is what we will use to build up our GTFS-realtime
     * feed. We will add alerts to the feed and then save the results.
     */
    FeedMessage.Builder feedMessage = GtfsRealtimeLibrary.createFeedMessageBuilder();

    /**
     * We iterate over every JSON alert object.
     */
    for (int i = 0; i < alertArray.length(); ++i) {

      JSONObject obj = alertArray.getJSONObject(i);

      /**
       * Extract the text from the alert. If there is no text, we don't create
       * an alert for this instance and continue on.
       */
      AlertText text = _alertTextExtractor.getAlertText(obj);
      if (text == null) {
        continue;
      }

      /**
       * Create out Alert.Builder for constructing the actual GTFS-realtime
       * alert.
       */
      Alert.Builder alert = Alert.newBuilder();

      /**
       * Set the the alert header and description text. Note that GTFS-realtime
       * supports providing text in multiple languages, but in this example, we
       * just specify a single message in the default language.
       */
      alert.setHeaderText(GtfsRealtimeLibrary.getTextAsTranslatedString(text.getTitle()));
      alert.setDescriptionText(GtfsRealtimeLibrary.getTextAsTranslatedString(text.getDescription()));

      /**
       * Every alert needs an id. Since one isn't included in the SEPTA JSON
       * alerts API, we generate one by taking the MD5-sum of the alert body.
       * The way the id will be the same if we see the same alert twice.
       */
      String id = getHashOfValue(obj.toString());

      /**
       * Every alert also needs to be attached to something: a route, a stop, a
       * trip, or maybe even some combination. The SEPTA feed provides route
       * information.
       */
      EntitySelector.Builder entitySelector = EntitySelector.newBuilder();
      entitySelector.setRouteId(obj.getString("route_id"));
      alert.addInformedEntity(entitySelector);

      /**
       * Create a new feed entity to wrap the alert and add it to the
       * GTFS-realtime feed message.
       */
      FeedEntity.Builder entity = FeedEntity.newBuilder();
      entity.setId(id);
      entity.setAlert(alert);
      feedMessage.addEntity(entity);
    }

    /**
     * Build out the final GTFS-realtime feed message and save it to the alerts
     * field.
     */
    _alerts = feedMessage.build();
    _log.info("alerts extracted: " + _alerts.getEntityCount());
  }

  /**
   * @return a JSON array parsed from the data pulled from the SEPTA alerts API.
   */
  private JSONArray downloadAlerts() throws IOException, JSONException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(
        _url.openStream()));
    JSONTokener tokener = new JSONTokener(reader);
    JSONArray alertArray = new JSONArray(tokener);
    return alertArray;
  }

  /**
   * Generates a unique fingerprint of the specified value.
   * 
   * @param value
   * @return an hex-string of the MD5 fingerprint of the specified value.
   */
  private String getHashOfValue(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      digest.update(value.getBytes());
      Hex hex = new Hex();
      return new String(hex.encode(digest.digest()), hex.getCharsetName());
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * Task that will download new alerts from the remote data source when
   * executed.
   */
  private class AlertRefreshTask implements Runnable {

    @Override
    public void run() {
      try {
        _log.info("refreshing alerts");
        refreshAlerts();
      } catch (Exception ex) {
        _log.warn("Error in alerts refresh task", ex);
      }
    }
  }

}
