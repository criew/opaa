package io.opaa.architecture.fixture.unregisteredclient.search;

import org.jsoup.Connection;
import org.jsoup.Jsoup;

/** Opens a connection through Jsoup without registering the data flow. */
public class PageScraper {

  public Connection page(String url) {
    return Jsoup.connect(url);
  }
}
