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

package org.exoplatform.chat.portlet.chat;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.portlet.PortletPreferences;

import org.apache.commons.fileupload.FileItem;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import org.exoplatform.addons.chat.listener.ServerBootstrap;
import org.exoplatform.chat.bean.File;
import org.exoplatform.chat.services.ChatService;
import org.exoplatform.chat.utils.PropertyManager;
import org.exoplatform.common.http.HTTPStatus;
import org.exoplatform.commons.api.ui.ActionContext;
import org.exoplatform.commons.api.ui.PlugableUIService;
import org.exoplatform.commons.api.ui.RenderContext;
import org.exoplatform.commons.juzu.ajax.Ajax;
import org.exoplatform.portal.webui.util.Util;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.ws.frameworks.cometd.ContinuationService;

import juzu.Path;
import juzu.Resource;
import juzu.Response;
import juzu.SessionScoped;
import juzu.View;
import juzu.impl.common.Tools;
import juzu.request.ApplicationContext;
import juzu.request.RequestContext;
import juzu.request.RequestParameter;
import juzu.request.SecurityContext;
import juzu.request.UserContext;
import juzu.template.Template;

@SessionScoped
public class ChatApplication
{

  @Inject
  @Path("index.gtmpl")
  Template index;

  String token_ = "---";
  String remoteUser_ = null;
  String fullname_ = null;
  boolean isAdmin_=false;
  Boolean isTeamAdmin_ = null;

  boolean profileInitialized_ = false;

  private static final Logger LOG = Logger.getLogger("ChatApplication");

  OrganizationService organizationService_;

  SpaceService spaceService_;

  PlugableUIService uiService;

  String dbName;

  @Inject
  Provider<PortletPreferences> providerPreferences;

  @Inject
  DocumentsData documentsData_;

  @Inject
  CalendarService calendarService_;

  @Inject
  ContinuationService continuationService;

  @Inject
  WikiService wikiService_;

  public static final String CHAT_EXTENSION_POPUP = "chat_extension_popup";

  public static final String CHAT_EXTENSION_MENU = "chat_extension_menu";

  private static final String EX_ACTION_NAME = "extension_action";

  @Inject
  public ChatApplication(OrganizationService organizationService, SpaceService spaceService, PlugableUIService uiService)
  {
    this.uiService = uiService;
    organizationService_ = organizationService;
    spaceService_ = spaceService;
    dbName = ServerBootstrap.getDBName();
  }

  @View
  public Response.Content index(ApplicationContext appContext, UserContext userContext, SecurityContext securityContext)
  {
    remoteUser_ = securityContext.getRemoteUser();
    String chatServerURI = ServerBootstrap.getServerURI();
    String chatIntervalSession = PropertyManager.getProperty(PropertyManager.PROPERTY_INTERVAL_SESSION);
    String plfUserStatusUpdateUrl = PropertyManager.getProperty(PropertyManager.PROPERTY_PLF_USER_STATUS_UPDATE_URL);
    String uploadFileSize = PropertyManager.getProperty(PropertyManager.PROPERTY_UPLOAD_FILE_SIZE);

    initChatProfile();

    String fullname = (fullname_==null || fullname_.isEmpty()) ? remoteUser_ : fullname_;

    PortletPreferences portletPreferences = providerPreferences.get();
    String view = portletPreferences.getValue("view", "responsive");
    if (!"normal".equals(view) && !"responsive".equals(view) && !"public".equals(view))
      view = "responsive";

    String fullscreen = portletPreferences.getValue("fullscreen", "false");

    DateFormat df = new SimpleDateFormat("MM/dd/yyyy");
    Date today = Calendar.getInstance().getTime();
    String todayDate = df.format(today);

    Locale locale = userContext.getLocale();
    ResourceBundle bundle = appContext.resolveBundle(locale);
    RenderContext exMenuCtx = new RenderContext(CHAT_EXTENSION_MENU);
    exMenuCtx.setRsBundle(bundle);
    List<org.exoplatform.commons.api.ui.Response> menuResponse = this.uiService.render(exMenuCtx);

    RenderContext exPopupCtx = new RenderContext(CHAT_EXTENSION_POPUP);
    exPopupCtx.setActionUrl(ChatApplication_.processAction().toString());
    exPopupCtx.setRsBundle(bundle);
    List<org.exoplatform.commons.api.ui.Response> popupResponse = this.uiService.render(exPopupCtx);

    StringBuilder extMenu = new StringBuilder();
    StringBuilder extPopup = new StringBuilder();
    try {
      for (org.exoplatform.commons.api.ui.Response menu : menuResponse) {
        extMenu.append(new String(menu.getData(), "UTF-8"));
      }
      for (org.exoplatform.commons.api.ui.Response popup : popupResponse) {
        extPopup.append(new String(popup.getData(), "UTF-8"));
      }
    } catch (Exception ex) {
      LOG.log(Level.SEVERE, ex.getMessage(), ex);
    }

    String portalURI = Util.getPortalRequestContext().getPortalURI();
    if(!portalURI.endsWith("/")){
      portalURI.concat("/");
    }

    return index.with().set("user", remoteUser_).set("room", "noroom")
            .set("token", token_)
            .set("chatServerURL", chatServerURI)
            .set("fullname", fullname)
            .set("teamAdmin", String.valueOf(isTeamAdmin_))
            .set("chatIntervalSession", chatIntervalSession)
            .set("plfUserStatusUpdateUrl", plfUserStatusUpdateUrl)
            .set("fullscreen", fullscreen)
            .set("today", todayDate)
            .set("dbName", dbName)
            .set("extPopup", extPopup)
            .set("extMenu", extMenu)
            .set("portalURI", portalURI)
            .set("uploadFileSize", uploadFileSize)
            .ok()
            .withMetaTag("viewport", "width=device-width, initial-scale=1.0")
            .withAssets("chat-" + view)
            .withCharset(Tools.UTF_8);

  }

  /**
   * Init Chat user profile
   */
  public void initChatProfile() {

    if (!profileInitialized_)
    {
      fullname_ = ServerBootstrap.getUserFullName(remoteUser_, dbName);
      try
      {
        // Generate and store token if doesn't exist yet.
        token_ = ServerBootstrap.getToken(remoteUser_);

        // Add User in the DB
        ServerBootstrap.addUser(remoteUser_, token_, dbName);

        // Set user's Full Name in the DB
        saveFullNameAndEmail(remoteUser_, dbName);

        if (isTeamAdmin_==null)
        {
          Collection ms = organizationService_.getMembershipHandler().findMembershipsByUserAndGroup(remoteUser_, PropertyManager.getProperty(PropertyManager.PROPERTY_TEAM_ADMIN_GROUP));
          isTeamAdmin_ = (ms!=null && ms.size()>0);
        }

        ServerBootstrap.setAsAdmin(remoteUser_, isAdmin_, dbName);

        // Set user's Spaces in the DB
        ServerBootstrap.saveSpaces(remoteUser_, dbName);

        profileInitialized_ = true;
      }
      catch (Exception e)
      {
        LOG.warning("Error while initializing chat user profile : " + e.getMessage());
        profileInitialized_ = false;
      }
    }
  }

  @Ajax
  @Resource
  public Response.Content maintainSession()
  {
    return Response.ok("OK").withMimeType("text/html; charset=UTF-8").withHeader("Cache-Control", "no-cache");
  }

  @Resource
  @Ajax
  public Response.Content upload(String room, String targetUser, String targetFullname, String encodedFileName, FileItem userfile, SecurityContext securityContext) {
    try {
      targetFullname = URLDecoder.decode(targetFullname, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      // Cannot do anything here
    }
    LOG.info("File is uploaded in " + room + " (" + targetFullname + ")");
    if (userfile.isFormField())
    {
      String fieldName = userfile.getFieldName();
      if ("room".equals(fieldName))
      {
        room = userfile.getString();
        LOG.info("room : " + room);
      }
    }
    if (userfile.getFieldName().equals("userfile"))
    {

      String uuid = null;
      if (targetUser.startsWith(ChatService.SPACE_PREFIX))
      {
        uuid = documentsData_.storeFile(userfile, encodedFileName, targetFullname, false);
      }
      else
      {
        remoteUser_ = securityContext.getRemoteUser();
        uuid = documentsData_.storeFile(userfile, encodedFileName, remoteUser_, true);
        documentsData_.setPermission(uuid, targetUser);
      }
      File file = documentsData_.getNode(uuid);

      LOG.info(file.toJSON());


      return Response.ok(file.toJSON())
              .withMimeType("application/json; charset=UTF-8").withHeader("Cache-Control", "no-cache").withCharset(Tools.UTF_8);
    }


    return Response.ok("{\"status\":\"File has not been uploaded !\"}")
            .withMimeType("application/json; charset=UTF-8").withHeader("Cache-Control", "no-cache");
  }

  @Ajax
  @Resource
  public Response processAction(RequestContext reqContext) {
    Map<String, RequestParameter> params = reqContext.getParameters();
    Map<String, List<String>> p = new HashMap<String, List<String>>();
    for (String name : params.keySet()) {
      p.put(name, Arrays.asList(params.get(name).toArray()));
    }
    //
    String actionName = params.get(EX_ACTION_NAME).getValue();
    ActionContext actContext = new ActionContext(CHAT_EXTENSION_POPUP, actionName);
    actContext.setParams(p);
    org.exoplatform.commons.api.ui.Response response = uiService.processAction(actContext);

    if (response != null) {
      return Response.ok(new String(response.getData(), Charset.forName("UTF-8")))
          .withMimeType("application/json; charset=UTF-8").withHeader("Cache-Control", "no-cache");
    } else {
      return Response.status(HTTPStatus.NOT_FOUND)
          .withHeader("Cache-Control", "no-cache");
    }
  }

  @Ajax
  @Resource
  public Response.Content createEvent(String space, String users, String summary, String startDate, String startTime, String endDate, String endTime, String location) {
    SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy hh:mm a");
    try {
      calendarService_.saveEvent(remoteUser_, space, users, summary, sdf.parse(startDate + " " + startTime),
              sdf.parse(endDate + " " + endTime), location);

    } catch (ParseException e) {
      LOG.warning("parse exception during event creation");
      return Response.notFound("Error during event creation");
    } catch (Exception e) {
      LOG.warning("exception during event creation");
      return Response.notFound("Error during event creation");
    }


    return Response.ok("{\"status\":\"ok\"}")
            .withMimeType("application/json; charset=UTF-8").withHeader("Cache-Control", "no-cache");

  }

  @Ajax
  @Resource
  public Response.Content saveWiki(String targetFullname, String content) {

    SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH-mm");
    String group = null, title = null, path="";
    JSONObject jsonObject = (JSONObject)JSONValue.parse(content);
    String typeRoom = (String)jsonObject.get("typeRoom");
    String xwiki = (String)jsonObject.get("xwiki");
    xwiki = xwiki.replaceAll("~", "~~");
    xwiki = xwiki.replaceAll("&#38", "&");
    xwiki = xwiki.replaceAll("&lt;", "<");
    xwiki = xwiki.replaceAll("&gt;", ">");
    xwiki = xwiki.replaceAll("&quot;","\"");
    xwiki = xwiki.replaceAll("<br/>","\n");
    xwiki = xwiki.replaceAll("&#92","\\\\");
    xwiki = xwiki.replaceAll("  ","\t");
    ArrayList<String> users = (ArrayList<String>) jsonObject.get("users");
    if (ChatService.TYPE_ROOM_SPACE.equalsIgnoreCase(typeRoom)) {
      Space spaceBean = spaceService_.getSpaceByDisplayName(targetFullname);
      if (spaceBean!=null) // Space use case
      {
        group = spaceBean.getGroupId();
        if (group.startsWith("/")) group = group.substring(1);
        title = "Meeting "+sdf.format(new Date());
        path = wikiService_.createSpacePage(remoteUser_, title, xwiki, group, users);
      }
    }
    else // Team use case & one to one use case
    {
      title = targetFullname+" Meeting "+sdf.format(new Date());
      path = wikiService_.createIntranetPage(remoteUser_, title, xwiki, users);
    }

    return Response.ok("{\"status\":\"ok\", \"path\":\""+path+"\"}")
            .withMimeType("application/json; charset=UTF-8").withHeader("Cache-Control", "no-cache").withCharset(Tools.UTF_8);

  }

  protected String saveFullNameAndEmail(String username, String dbName)
  {
    String fullname = username;
    try
    {

      fullname = ServerBootstrap.getUserFullName(username, dbName);
      if (fullname==null || fullname.isEmpty())
      {
        User user = organizationService_.getUserHandler().findUserByName(username);
        if (user!=null)
        {
          fullname = user.getFirstName()+" "+user.getLastName();
          ServerBootstrap.addUserFullNameAndEmail(username, fullname, user.getEmail(), dbName);
        }
      }
    }
    catch (Exception e)
    {
      LOG.warning(e.getMessage());
    }
    return fullname;
  }

}
