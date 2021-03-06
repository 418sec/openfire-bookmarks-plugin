/**
 * Copyright (C) 1999-2008 Jive Software. All rights reserved.
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

package org.jivesoftware.openfire.plugin.spark;

import java.util.Collection;
import java.util.Iterator;

import org.dom4j.Element;
import org.jivesoftware.openfire.*;
import org.jivesoftware.openfire.group.Group;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.jivesoftware.util.NotFoundException;

/**
 * Intercepts Bookmark Storage requests and appends all server based Bookmarks to
 * the result.
 *
 * @author Derek DeMoro
 */
public class BookmarkInterceptor implements PacketInterceptor {

    private static final Logger Log = LoggerFactory.getLogger(BookmarkInterceptor.class);

    /**
     * Initializes the BookmarkInterceptor and needed Server instances.
     */
    public BookmarkInterceptor() {
    }

    public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) throws PacketRejectedException {
        if (!processed && packet instanceof IQ) {

            // Check for the Bookmark Storage element and hand off to the Bookmark engine.
            IQ iq = (IQ)packet;
            Element childElement = iq.getChildElement();
            if (childElement == null) {
                return;
            }

            String namespace = childElement.getNamespaceURI();

            if ("jabber:iq:private".equals(namespace) && iq.getType() == IQ.Type.result && !incoming) {
                // In private data, when a user is attempting to retrieve bookmark
                // information, there will be a storage:bookmarks namespace.
                Element storageElement = childElement.element("storage");
                if (storageElement == null) {
                    return;
                }

                namespace = storageElement.getNamespaceURI();
                if ("storage:bookmarks".equals(namespace)) {
                    // Append Server defined bookmarks for user.
                    JID toJID = iq.getTo();
                    addBookmarks(toJID, storageElement);
                }
            }
            else

            if ("vcard-temp".equals(namespace)) {
                String key = null;

                if (iq.getType() == IQ.Type.get && incoming && iq.getTo() != null)
                {
                    key = iq.getTo().toString();
                }
                else

                if (iq.getType() == IQ.Type.error && !incoming && iq.getFrom() != null)
                {
                    key = iq.getFrom().toString();
                }

                if (key != null)
                {
                    try {
                        Bookmark bookmark = BookmarkManager.getBookmark(key);
                        String avatarUri = bookmark.getProperty("avatar_uri");

                        if (avatarUri != null)
                        {
                            if (iq.getType() == IQ.Type.get)
                            {
                                String[] avatar = avatarUri.split(";base64,");
                                String mime = avatar[0].substring(5);
                                String bin = avatar[1];

                                IQ reply = IQ.createResultIQ(iq);
                                Element child = reply.setChildElement("vCard", "vcard-temp");
                                child.addElement("FN").setText(bookmark.getName());
                                child.addElement("NICKNAME").setText(bookmark.getName());

                                Element photo = child.addElement("PHOTO");
                                photo.addElement("TYPE").setText(mime);
                                photo.addElement("BINVAL").setText(bin);

                                Log.debug("interceptPacket reply \n" + reply);
                                XMPPServer.getInstance().getIQRouter().route(reply);
                            }

                            throw new PacketRejectedException("handled by bookmarks plugin");
                        }
                    }
                    catch (NotFoundException e) {
                        // do nothing
                    }
                }
            }
        }
    }

    /**
     * Add this interceptor to the interceptor manager.
     */
    public void start() {
        InterceptorManager.getInstance().addInterceptor(this);
    }

    /**
     * Remove this interceptor from the interceptor manager.
     */
    public void stop() {
        InterceptorManager.getInstance().removeInterceptor(this);
    }

    /**
     * Adds all server defined bookmarks to the users requested
     * bookmarks.
     *
     * @param jid            the jid of the user requesting the bookmark(s)
     * @param storageElement the JEP-0048 compliant storage element.
     */
    private void addBookmarks(JID jid, Element storageElement) {
        try {
            final Collection<Bookmark> bookmarks = BookmarkManager.getBookmarks();

            for (Bookmark bookmark : bookmarks) {
                // Check to see if the bookmark should be appended for this
                // particular user.
                boolean addBookmarkForUser = bookmark.isGlobalBookmark() || isBookmarkForJID(jid, bookmark);
                if (addBookmarkForUser) {
                    // Add bookmark element.
                    addBookmarkElement(jid, bookmark, storageElement);
                }
            }
        } catch (Exception e) {
            Log.error("addBookmarks", e);
        }
    }

    /**
     * True if the specified bookmark should be appended to the users list of
     * bookmarks.
     *
     * @param jid      the jid of the user.
     * @param bookmark the bookmark.
     * @return true if bookmark should be appended.
     */
    private static boolean isBookmarkForJID(JID jid, Bookmark bookmark) {
        String username = jid.getNode();

        if (bookmark.getUsers().contains(username)) {
            return true;
        }

        Collection<String> groups = bookmark.getGroups();

        if (groups != null && !groups.isEmpty()) {
            GroupManager groupManager = GroupManager.getInstance();
            for (String groupName : groups) {
                try {
                    Group group = groupManager.getGroup(groupName);
                    if (group.isUser(jid.getNode())) {
                        return true;
                    }
                }
                catch (GroupNotFoundException e) {
                    Log.debug(e.getMessage(), e);
                }
            }
        }
        return false;
    }

    /**
     * Adds a Bookmark to the users defined list of bookmarks.
     *
     * @param jid      the users jid.
     * @param bookmark the bookmark to be added.
     * @param element  the storage element to append to.
     */
    private void addBookmarkElement(JID jid, Bookmark bookmark, Element element) {
        final UserManager userManager = UserManager.getInstance();

        try {
            userManager.getUser(jid.getNode());
        }
        catch (UserNotFoundException e) {
            return;
        }

        // If this is a URL Bookmark, check to make sure we
        // do not add duplicate bookmarks.
        if (bookmark.getType() == Bookmark.Type.url) {
            Element urlBookmarkElement = urlExists(element, bookmark.getValue());

            if (urlBookmarkElement == null) {
                urlBookmarkElement = element.addElement("url");
                urlBookmarkElement.addAttribute("name", bookmark.getName());
                urlBookmarkElement.addAttribute("url", bookmark.getValue());
                // Add an RSS attribute to the bookmark if it's defined. RSS isn't an
                // official part of the Bookmark JEP, but we define it as a logical
                // extension.
                boolean rss = Boolean.valueOf(bookmark.getProperty("rss"));
                if (rss) {
                    urlBookmarkElement.addAttribute("rss", Boolean.toString(rss));
                }

                boolean webapp = Boolean.valueOf(bookmark.getProperty("webapp"));
                if (webapp) {
                    urlBookmarkElement.addAttribute("webapp", Boolean.toString(webapp));
                }

                boolean collabapp = Boolean.valueOf(bookmark.getProperty("collabapp"));
                if (collabapp) {
                    urlBookmarkElement.addAttribute("collabapp", Boolean.toString(collabapp));
                }

                boolean homepage = Boolean.valueOf(bookmark.getProperty("homepage"));
                if (homepage) {
                    urlBookmarkElement.addAttribute("homepage", Boolean.toString(homepage));
                }
            }
            appendSharedElement(urlBookmarkElement);
        }
        // Otherwise it's a conference bookmark.
        else {

            try {
                Element conferenceElement = conferenceExists(element, bookmark.getValue());

                // If the conference bookmark does not exist, add it to the current
                // reply.
                if (conferenceElement == null) {
                    conferenceElement = element.addElement("conference");
                    conferenceElement.addAttribute("name", bookmark.getName());
                    boolean autojoin = Boolean.valueOf(bookmark.getProperty("autojoin"));
                    conferenceElement.addAttribute("autojoin", Boolean.toString(autojoin));
                    conferenceElement.addAttribute("jid", bookmark.getValue());
                    boolean nameasnick = Boolean.valueOf(bookmark.getProperty("nameasnick"));
                    if (nameasnick) {
                        User currentUser = userManager.getUser(jid.getNode());
                        Element nick = conferenceElement.addElement("nick");
                        nick.addText(currentUser.getName());
                    }
                    if (bookmark.getProperty("avatar_uri") != null) {
                       conferenceElement.addAttribute("avatar_uri", bookmark.getProperty("avatar_uri"));
                    }

                    boolean ofmeet_recording = Boolean.valueOf(bookmark.getProperty("ofmeet_recording"));
                    if (ofmeet_recording) conferenceElement.addAttribute("ofmeet_recording", Boolean.toString(ofmeet_recording));

                    boolean ofmeet_tags = Boolean.valueOf(bookmark.getProperty("ofmeet_tags"));
                    if (ofmeet_tags) conferenceElement.addAttribute("ofmeet_tags", Boolean.toString(ofmeet_tags));

                    boolean ofmeet_cryptpad = Boolean.valueOf(bookmark.getProperty("ofmeet_cryptpad"));
                    if (ofmeet_cryptpad) conferenceElement.addAttribute("ofmeet_cryptpad", Boolean.toString(ofmeet_cryptpad));

                    boolean ofmeet_captions = Boolean.valueOf(bookmark.getProperty("ofmeet_captions"));
                    if (ofmeet_captions) conferenceElement.addAttribute("ofmeet_captions", Boolean.toString(ofmeet_captions));

                    boolean ofmeet_transcription = Boolean.valueOf(bookmark.getProperty("ofmeet_transcription"));
                    if (ofmeet_transcription) conferenceElement.addAttribute("ofmeet_transcription", Boolean.toString(ofmeet_transcription));

                    boolean ofmeet_uploads = Boolean.valueOf(bookmark.getProperty("ofmeet_uploads"));
                    if (ofmeet_uploads) conferenceElement.addAttribute("ofmeet_uploads", Boolean.toString(ofmeet_uploads));

                    boolean ofmeet_breakout = Boolean.valueOf(bookmark.getProperty("ofmeet_breakout"));
                    if (ofmeet_breakout) conferenceElement.addAttribute("ofmeet_breakout", Boolean.toString(ofmeet_breakout));
                }
                appendSharedElement(conferenceElement);

            }
            catch (Exception e) {
                Log.error(e.getMessage(), e);
            }
        }
    }

    /**
     * Adds the shared namespace element to indicate to clients that this bookmark is a shared bookmark.
     *
     * @param bookmarkElement the bookmark to add the shared element to.
     */
    private static void appendSharedElement(Element bookmarkElement) {
        bookmarkElement.addElement("shared_bookmark", "http://jivesoftware.com/jeps/bookmarks");
    }

    /**
     * Checks if the bookmark has already been defined in the users private storage.
     *
     * @param element the private storage element.
     * @param url     the url to search for.
     * @return true if the bookmark already exists.
     */
    private static Element urlExists(Element element, String url) {
        // Iterate through current elements to see if this url already exists.
        // If one does not exist, then add the bookmark.
        final Iterator<Element> urlBookmarks = element.elementIterator("url");
        while (urlBookmarks.hasNext()) {
            Element urlElement = urlBookmarks.next();
            String urlValue = urlElement.attributeValue("url");
            if (urlValue.equalsIgnoreCase(url)) {
                return urlElement;
            }
        }

        return null;
    }

    /**
     * Checks if the conference bookmark has already been defined in the users private storage.
     *
     * @param element  the private storage element.
     * @param roomJID the JID of the room to find.
     * @return true if the bookmark exists.
     */
    private Element conferenceExists(Element element, String roomJID) {
        // Iterate through current elements to see if the conference bookmark
        // already exists.
        final Iterator<Element> conferences = element.elementIterator("conference");
        while (conferences.hasNext()) {
            final Element conferenceElement = conferences.next();
            String jidValue = conferenceElement.attributeValue("jid");

            if (jidValue != null && roomJID != null && jidValue.equalsIgnoreCase(roomJID)) {
                return conferenceElement;
            }
        }
        return null;
    }
}
