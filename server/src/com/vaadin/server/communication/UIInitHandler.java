/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.server.communication;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import com.vaadin.annotations.PreserveOnRefresh;
import com.vaadin.server.LegacyApplicationUIProvider;
import com.vaadin.server.SynchronizedRequestHandler;
import com.vaadin.server.UIClassSelectionEvent;
import com.vaadin.server.UICreateEvent;
import com.vaadin.server.UIProvider;
import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinResponse;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinSession;
import com.vaadin.shared.ApplicationConstants;
import com.vaadin.shared.communication.PushMode;
import com.vaadin.shared.ui.ui.UIConstants;
import com.vaadin.ui.UI;

/**
 * Handles an initial request from the client to initialize a {@link UI}.
 * 
 * @author Vaadin Ltd
 * @since 7.1
 */
public abstract class UIInitHandler extends SynchronizedRequestHandler {

    public static final String BROWSER_DETAILS_PARAMETER = "v-browserDetails";

    protected abstract boolean isInitRequest(VaadinRequest request);

    @Override
    public boolean synchronizedHandleRequest(VaadinSession session,
            VaadinRequest request, VaadinResponse response) throws IOException {
        if (!isInitRequest(request)) {
            return false;
        }

        StringWriter stringWriter = new StringWriter();

        try {
            assert UI.getCurrent() == null;

            // Set browser information from the request
            session.getBrowser().updateRequestDetails(request);

            UI uI = getBrowserDetailsUI(request, session);

            session.getCommunicationManager().repaintAll(uI);

            JSONObject params = new JSONObject();
            params.put(UIConstants.UI_ID_PARAMETER, uI.getUIId());
            String initialUIDL = getInitialUidl(request, uI);
            params.put("uidl", initialUIDL);

            stringWriter.write(params.toString());
        } catch (JSONException e) {
            throw new IOException("Error producing initial UIDL", e);
        } finally {
            stringWriter.close();
        }

        return commitJsonResponse(request, response, stringWriter.toString());
    }

    /**
     * Commit the JSON response. We can't write immediately to the output stream
     * as we want to write only a critical notification if something goes wrong
     * during the response handling.
     * 
     * @param request
     *            The request that resulted in this response
     * @param response
     *            The response to write to
     * @param json
     *            The JSON to write
     * @return true if the JSON was written successfully, false otherwise
     * @throws IOException
     *             If there was an exception while writing to the output
     */
    static boolean commitJsonResponse(VaadinRequest request,
            VaadinResponse response, String json) throws IOException {
        // The response was produced without errors so write it to the client
        response.setContentType("application/json; charset=UTF-8");

        // Ensure that the browser does not cache UIDL responses.
        // iOS 6 Safari requires this (#9732)
        response.setHeader("Cache-Control", "no-cache");

        // NOTE! GateIn requires, for some weird reason, getOutputStream
        // to be used instead of getWriter() (it seems to interpret
        // application/json as a binary content type)
        OutputStreamWriter outputWriter = new OutputStreamWriter(
                response.getOutputStream(), "UTF-8");
        try {
            outputWriter.write(json);
            // NOTE GateIn requires the buffers to be flushed to work
            outputWriter.flush();
        } finally {
            outputWriter.close();
        }

        return true;
    }

    private UI getBrowserDetailsUI(VaadinRequest request, VaadinSession session) {
        VaadinService vaadinService = request.getService();

        List<UIProvider> uiProviders = session.getUIProviders();

        UIClassSelectionEvent classSelectionEvent = new UIClassSelectionEvent(
                request);

        UIProvider provider = null;
        Class<? extends UI> uiClass = null;
        for (UIProvider p : uiProviders) {
            // Check for existing LegacyWindow
            if (p instanceof LegacyApplicationUIProvider) {
                LegacyApplicationUIProvider legacyProvider = (LegacyApplicationUIProvider) p;

                UI existingUi = legacyProvider
                        .getExistingUI(classSelectionEvent);
                if (existingUi != null) {
                    reinitUI(existingUi, request);
                    return existingUi;
                }
            }

            uiClass = p.getUIClass(classSelectionEvent);
            if (uiClass != null) {
                provider = p;
                break;
            }
        }

        if (provider == null || uiClass == null) {
            return null;
        }

        // Check for an existing UI based on window.name

        // Special parameter sent by vaadinBootstrap.js
        String windowName = request.getParameter("v-wn");

        Map<String, Integer> retainOnRefreshUIs = session
                .getPreserveOnRefreshUIs();
        if (windowName != null && !retainOnRefreshUIs.isEmpty()) {
            // Check for a known UI

            Integer retainedUIId = retainOnRefreshUIs.get(windowName);

            if (retainedUIId != null) {
                UI retainedUI = session.getUIById(retainedUIId.intValue());
                if (uiClass.isInstance(retainedUI)) {
                    reinitUI(retainedUI, request);
                    return retainedUI;
                } else {
                    getLogger().info(
                            "Not using retained UI in " + windowName
                                    + " because retained UI was of type "
                                    + retainedUI.getClass() + " but " + uiClass
                                    + " is expected for the request.");
                }
            }
        }

        // No existing UI found - go on by creating and initializing one

        Integer uiId = Integer.valueOf(session.getNextUIid());

        // Explicit Class.cast to detect if the UIProvider does something
        // unexpected
        UICreateEvent event = new UICreateEvent(request, uiClass, uiId);
        UI ui = uiClass.cast(provider.createInstance(event));

        // Initialize some fields for a newly created UI
        if (ui.getSession() != session) {
            // Session already set for LegacyWindow
            ui.setSession(session);
        }

        PushMode pushMode = provider.getPushMode(event);
        if (pushMode == null) {
            pushMode = session.getService().getDeploymentConfiguration()
                    .getPushMode();
        }
        ui.setPushMode(pushMode);

        // Set thread local here so it is available in init
        UI.setCurrent(ui);

        ui.doInit(request, uiId.intValue());

        session.addUI(ui);

        // Remember if it should be remembered
        if (vaadinService.preserveUIOnRefresh(provider, event)) {
            // Remember this UI
            if (windowName == null) {
                getLogger().warning(
                        "There is no window.name available for UI " + uiClass
                                + " that should be preserved.");
            } else {
                session.getPreserveOnRefreshUIs().put(windowName, uiId);
            }
        }

        return ui;
    }

    /**
     * Updates a UI that has already been initialized but is now loaded again,
     * e.g. because of {@link PreserveOnRefresh}.
     * 
     * @param ui
     * @param request
     */
    private void reinitUI(UI ui, VaadinRequest request) {
        UI.setCurrent(ui);

        // Fire fragment change if the fragment has changed
        String location = request.getParameter("v-loc");
        if (location != null) {
            ui.getPage().updateLocation(location);
        }
    }

    /**
     * Generates the initial UIDL message that can e.g. be included in a html
     * page to avoid a separate round trip just for getting the UIDL.
     * 
     * @param request
     *            the request that caused the initialization
     * @param uI
     *            the UI for which the UIDL should be generated
     * @return a string with the initial UIDL message
     * @throws JSONException
     *             if an exception occurs while encoding output
     * @throws IOException
     */
    protected String getInitialUidl(VaadinRequest request, UI uI)
            throws JSONException, IOException {
        StringWriter writer = new StringWriter();
        try {
            writer.write("{");

            VaadinSession session = uI.getSession();
            if (session.getConfiguration().isXsrfProtectionEnabled()) {
                writer.write(getSecurityKeyUIDL(session));
            }
            new UidlWriter().write(uI, writer, true, false, false);
            writer.write("}");

            String initialUIDL = writer.toString();
            getLogger().log(Level.FINE, "Initial UIDL:" + initialUIDL);
            return initialUIDL;
        } finally {
            writer.close();
        }
    }

    /**
     * Gets the security key (and generates one if needed) as UIDL.
     * 
     * @param session
     *            the vaadin session to which the security key belongs
     * @return the security key UIDL or "" if the feature is turned off
     */
    private static String getSecurityKeyUIDL(VaadinSession session) {
        String seckey = session.getCsrfToken();

        return "\"" + ApplicationConstants.UIDL_SECURITY_TOKEN_ID + "\":\""
                + seckey + "\",";
    }

    private static final Logger getLogger() {
        return Logger.getLogger(UIInitHandler.class.getName());
    }
}