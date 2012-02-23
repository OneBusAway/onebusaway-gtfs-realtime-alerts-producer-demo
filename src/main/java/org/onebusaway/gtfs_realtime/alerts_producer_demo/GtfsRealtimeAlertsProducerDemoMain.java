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

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Parser;
import org.onebusaway.cli.CommandLineInterfaceLibrary;
import org.onebusaway.guice.jetty_exporter.JettyExporterModule;
import org.onebusaway.guice.jsr250.JSR250Module;
import org.onebusaway.guice.jsr250.LifecycleService;
import org.onebusway.gtfs_realtime.exporter.AlertsServlet;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeExporterModule;
import org.onebusway.gtfs_realtime.exporter.TripUpdatesFileWriter;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

public class GtfsRealtimeAlertsProducerDemoMain {

  private static final String ARG_ALERTS_PATH = "alertsPath";

  private static final String ARG_ALERTS_URL = "alertsUrl";

  public static void main(String[] args) throws Exception {
    GtfsRealtimeAlertsProducerDemoMain m = new GtfsRealtimeAlertsProducerDemoMain();
    m.run(args);
  }

  private GtfsRealtimeProviderImpl _provider;

  private LifecycleService _lifecycleService;

  @Inject
  public void setProvider(GtfsRealtimeProviderImpl provider) {
    _provider = provider;
  }

  @Inject
  public void setLifecycleService(LifecycleService lifecycleService) {
    _lifecycleService = lifecycleService;
  }

  public void run(String[] args) throws Exception {

    if (args.length == 0 || CommandLineInterfaceLibrary.wantsHelp(args)) {
      printUsage();
      System.exit(-1);
    }

    Options options = new Options();
    buildOptions(options);
    Parser parser = new GnuParser();
    CommandLine cli = parser.parse(options, args);

    List<Module> modules = new ArrayList<Module>();
    modules.add(new JSR250Module());
    modules.add(new JettyExporterModule());
    modules.add(new GtfsRealtimeExporterModule());
    modules.add(new GtfsRealtimeAlertsProducerDemoModule());

    Injector injector = Guice.createInjector(modules);
    injector.injectMembers(this);

    _provider.setUrl(new URL(
        "http://www3.septa.org/hackathon/Alerts/get_alert_data.php?req1=all"));

    if (cli.hasOption(ARG_ALERTS_URL)) {
      URL url = new URL(cli.getOptionValue(ARG_ALERTS_URL));
      AlertsServlet servlet = injector.getInstance(AlertsServlet.class);
      servlet.setUrl(url);
    }
    if (cli.hasOption(ARG_ALERTS_PATH)) {
      File path = new File(cli.getOptionValue(ARG_ALERTS_PATH));
      TripUpdatesFileWriter writer = injector.getInstance(TripUpdatesFileWriter.class);
      writer.setPath(path);
    }

    _lifecycleService.start();
  }

  private void printUsage() {
    CommandLineInterfaceLibrary.printUsage(getClass());
  }

  protected void buildOptions(Options options) {
    options.addOption(ARG_ALERTS_PATH, true, "trip updates path");
    options.addOption(ARG_ALERTS_URL, true, "trip updates url");
  }
}
