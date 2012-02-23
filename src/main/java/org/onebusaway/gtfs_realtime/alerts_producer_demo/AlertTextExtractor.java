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

import java.io.StringReader;
import java.io.StringWriter;

import javax.inject.Singleton;

import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.tidy.Tidy;

/**
 * This class examines the SEPTA-specific alert format returned by the SEPTA
 * alerts API and attempt to extract out alert title and description text, where
 * appropriate. SEPTA's alerts include snippets of HTML, which we parse to
 * extract the title and description.
 * 
 * @author bdferris
 */
@Singleton
public class AlertTextExtractor {

  /**
   * Attempts to extract alert title and description text from a SEPTA-specific
   * JSON alert object.
   * 
   * @param alertObject the JSON alert object
   * @return the extracted alert text, or null if none was found.
   * @throws JSONException
   */
  public AlertText getAlertText(JSONObject alertObject) throws JSONException {
    if (alertObject.has("advisory_message")) {
      String text = alertObject.getString("advisory_message");
      if (!text.isEmpty()) {
        return processHtml(text);
      }
    }
    return null;
  }

  /****
   * Private Methods
   ****/

  /**
   * @param html
   * @return an alert text object with title and description
   */
  private AlertText processHtml(String html) {
    Tidy tidy = new Tidy();
    tidy.setShowWarnings(false);
    Document doc = tidy.parseDOM(new StringReader(html), new StringWriter());
    StringBuilder title = new StringBuilder();
    StringBuilder description = new StringBuilder();
    processHtmlNode(doc, title, description);
    return new AlertText(title.toString(), description.toString());
  }

  /**
   * Recursively process an HTML document, looking for title and description
   * sections.
   * 
   * @param node
   * @param title
   * @param description
   */
  private void processHtmlNode(Node node, StringBuilder title,
      StringBuilder description) {
    switch (node.getNodeType()) {
      case Node.ELEMENT_NODE: {
        Element element = (Element) node;
        String name = element.getNodeName();
        /**
         * SEPTA appears to put their titles in <h3>elements.
         */
        if (name.equals("h3")) {
          buildTextFromNode(node, title);
          return;

        }
        /**
         * SEPTA appears to put description text in
         * <p>
         * elements.
         */
        if (name.equals("p")) {
          buildTextFromNode(node, description);
          return;
        }
        break;
      }
    }

    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      processHtmlNode(child, title, description);
    }
  }

  /**
   * For a given node, extract all text from the node and any children and
   * append it to the specified StringBuilder in an intelligent way.
   * 
   * @param node
   * @param output
   */
  private void buildTextFromNode(Node node, StringBuilder output) {
    if (node.getNodeType() == Node.TEXT_NODE) {
      if (output.length() > 0 && output.charAt(output.length() - 1) != ' ') {
        output.append(' ');
      }
      output.append(node.getNodeValue());
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      buildTextFromNode(child, output);
    }
  }
}
