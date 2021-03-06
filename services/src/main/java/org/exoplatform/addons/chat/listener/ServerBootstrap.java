/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.exoplatform.addons.chat.listener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import org.exoplatform.chat.model.SpaceBean;
import org.exoplatform.chat.model.SpaceBeans;
import org.exoplatform.chat.utils.ChatUtils;
import org.exoplatform.chat.utils.MessageDigester;
import org.exoplatform.chat.utils.PropertyManager;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.portal.webui.util.Util;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

public class ServerBootstrap {

  private static final Log LOG = ExoLogger.getLogger(ServerBootstrap.class.getName());

  private static String serverURL;
  private static String serverURI;

  /**
   * Get mongo database name for current tenant if on cloud environment
   */
  public static String getDBName() {
    String dbName = "";
    String prefixDB = PropertyManager.getProperty(PropertyManager.PROPERTY_DB_NAME);
    ConversationState currentState = ConversationState.getCurrent();
    if (currentState != null) {
      dbName = (String) currentState.getAttribute("currentTenant");
    }
    if (StringUtils.isEmpty(dbName)) {
      dbName = prefixDB;
    } else {
      StringBuilder sb = new StringBuilder()
                                    .append(prefixDB)
                                    .append("_")
                                    .append(dbName);
      dbName = sb.toString();
    }
    return dbName;
  }

  public static String getUserFullName(String username, String dbName)
  {
    return callServer("getUserFullName", "username="+username+"&dbName="+dbName);
  }

  public static void addUser(String username, String token, String dbName)
  {
    postServer("addUser", "username="+username+"&token="+token+"&dbName="+dbName);
  }

  public static void setAsAdmin(String username, boolean isAdmin, String dbName)
  {
    postServer("setAsAdmin", "username="+username+"&isAdmin="+isAdmin+"&dbName="+dbName);
  }

  public static void addUserFullNameAndEmail(String username, String fullname, String email, String dbName)
  {
    try {
      postServer("addUserFullNameAndEmail", "username=" + username + "&fullname=" + ChatUtils.toString(fullname) + "&email=" + email + "&dbName=" + dbName);
    } catch (IOException e) {
      LOG.error("Error while updating user information for user {} [ {} ]", username, email,e);
    }
  }

  public static String getToken(String username)
  {
    String passphrase = PropertyManager.getProperty(PropertyManager.PROPERTY_PASSPHRASE);
    String in = username+passphrase;
    String token = MessageDigester.getHash(in);
    return token;
  }

  public static void saveSpaces(String username, String dbName)
  {
    try {
      SpaceService spaceService = CommonsUtils.getService(SpaceService.class);
      ListAccess<Space> spacesListAccess = spaceService.getAccessibleSpacesWithListAccess(username);
      List<Space> spaces = Arrays.asList(spacesListAccess.load(0, spacesListAccess.getSize()));
      ArrayList<SpaceBean> beans = new ArrayList<SpaceBean>();
      for (Space space : spaces) {
        SpaceBean spaceBean = new SpaceBean();
        spaceBean.setDisplayName(space.getDisplayName());
        spaceBean.setGroupId(space.getGroupId());
        spaceBean.setId(space.getId());
        spaceBean.setShortName(space.getShortName());
        beans.add(spaceBean);
      }
      setSpaces(username, new SpaceBeans(beans), dbName);
    } catch (Exception e) {
      LOG.warn("Error while initializing spaces of User '" + username + "'", e);
    }
  }

  public static void setSpaces(String username, SpaceBeans beans, String dbName)
  {
    String params = "username="+username;
    String serSpaces = "";
    try {
      serSpaces = ChatUtils.toString(beans);
      serSpaces = URLEncoder.encode(serSpaces, "UTF-8");
    } catch (IOException e) {
      LOG.error("Error encoding spaces",e);
    }
    params += "&spaces="+serSpaces;
    params += "&dbName="+dbName;
    postServer("setSpaces", params);
  }

  private static String callServer(String serviceUri, String params)
  {
    String serviceUrl = getServerURL()
            +"/"+serviceUri+"?passphrase="+PropertyManager.getProperty(PropertyManager.PROPERTY_PASSPHRASE)
            +"&"+params;
    String body = null;
    try {
      URL url = new URL(serviceUrl);
      URLConnection con = url.openConnection();
      InputStream in = con.getInputStream();
      String encoding = con.getContentEncoding();
      encoding = encoding == null ? "UTF-8" : encoding;
      body = IOUtils.toString(in, encoding);
      if ("null".equals(body)) body = null;
    } catch (MalformedURLException e) {
      LOG.error("Malformed URL {}",serviceUrl,e);
    } catch (IOException e) {
      LOG.error("Could not establish connection to URL {}",serviceUri,e);
    } catch (Exception e) {
      LOG.error("Error occurred while sending request to " + serviceUrl, e);
    }
    return body;
  }

  private static String postServer(String serviceUri, String params)
  {
    String serviceUrl = getServerURL()
            +"/"+serviceUri;
    String allParams = "passphrase="+PropertyManager.getProperty(PropertyManager.PROPERTY_PASSPHRASE) + "&" + params;
    String body = null;
    OutputStreamWriter writer = null;
    try {
      URL url = new URL(serviceUrl);
      URLConnection con = url.openConnection();
      con.setDoOutput(true);

      //envoi de la requête
      writer = new OutputStreamWriter(con.getOutputStream());
      writer.write(allParams);
      writer.flush();

      InputStream in = con.getInputStream();
      String encoding = con.getContentEncoding();
      encoding = encoding == null ? "UTF-8" : encoding;
      body = IOUtils.toString(in, encoding);
      if ("null".equals(body)) body = null;

    } catch (MalformedURLException e) {
      LOG.error("Malformed URL " + serviceUri, e);
    } catch (IOException e) {
      LOG.error("Error converting input stream", e);
    } catch (Exception e) {
      LOG.error("Error occurred while sending request to " + serviceUrl, e);
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (Exception e) {
          LOG.error("Error when closing writer", e);
        }
      }
    }
    return body;
  }

  public static String getServerBase()
  {
    String serverBase = PropertyManager.getProperty(PropertyManager.PROPERTY_CHAT_SERVER_BASE);
    if ("".equals(serverBase)) {
      HttpServletRequest request = Util.getPortalRequestContext().getRequest();
      String scheme = request.getScheme();
      String serverName = request.getServerName();
      int serverPort= request.getServerPort();
      serverBase = scheme+"://"+serverName;
      if (serverPort != 80) serverBase += ":" + serverPort;
    }

    return serverBase;

  }

  public static String getServerURL() {
    if (serverURL == null) {
      String chatServerURL = PropertyManager.getProperty(PropertyManager.PROPERTY_CHAT_SERVER_URL);
      if (chatServerURL.startsWith("http")) {
        serverURL = chatServerURL;
      } else {
        serverURL = getServerBase() + getServerURI();
      }
    }
    return serverURL;
  }

  public static String getServerURI() {
    if (serverURI == null) {
      serverURI = PropertyManager.getProperty(PropertyManager.PROPERTY_CHAT_SERVER_URL);
    }
    return serverURI;
  }

}
