/*
 * Copyright 2026 Crown Copyright
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

package stroom.pathways.client.presenter;

import stroom.core.client.ContentManager;
import stroom.core.client.event.CloseContentEvent;
import stroom.pathways.shared.PathwaysDoc;

import com.google.inject.Inject;
import com.google.inject.Provider;

import java.util.HashMap;
import java.util.Map;

/**
 * The pathways open as tabs, one tab per pathway.
 *
 * <p>Held here rather than by whatever opened them, because a tab outlives the screen it was opened
 * from: closing the pathways document leaves its pathways open, and reopening it must not put a
 * second tab beside each of them.
 */
public class PathwayTabManager {

    private final ContentManager contentManager;
    private final Provider<PathwayEditPresenter> presenterProvider;
    private final Map<String, PathwayEditPresenter> open = new HashMap<>();

    @Inject
    public PathwayTabManager(final ContentManager contentManager,
                             final Provider<PathwayEditPresenter> presenterProvider) {
        this.contentManager = contentManager;
        this.presenterProvider = presenterProvider;
    }

    /**
     * Opens one pathway, or brings its tab to the front where it is open already. A pathway open a
     * second time is not read again, so whatever the reader had set up on its tab is kept.
     *
     * @param onChanged told whenever a change to this pathway's model sent from its tab lands, so a
     *                  list showing it can be brought up to date. Kept only until the tab is closed.
     */
    public void open(final PathwaysDoc pathwaysDoc,
                     final String name,
                     final boolean readOnly,
                     final Runnable onChanged) {
        final String key = key(pathwaysDoc, name);
        final PathwayEditPresenter existing = open.get(key);
        if (existing != null) {
            existing.setChangedHandler(onChanged);
            // Opening a tab that is already open selects it rather than adding a second.
            contentManager.open(closeHandler(key), existing, existing, existing, p -> existing.onOpened());
            return;
        }

        final PathwayEditPresenter presenter = presenterProvider.get();
        open.put(key, presenter);
        presenter.setChangedHandler(onChanged);
        presenter.read(pathwaysDoc, name, readOnly);
        // Told once the tab is open, because that is when there is something laid out to measure.
        contentManager.open(closeHandler(key), presenter, presenter, presenter, p -> presenter.onOpened());
    }

    private CloseContentEvent.Handler closeHandler(final String key) {
        return event -> {
            final PathwayEditPresenter presenter = open.get(key);
            if (presenter == null) {
                event.getCallback().closeTab(true);
                return;
            }
            // Asked rather than told, because the tab stops stepping through its changes before it
            // goes.
            presenter.onCloseRequest(new CloseContentEvent(event.getDirtyMode(), ok -> {
                if (ok) {
                    open.remove(key);
                }
                event.getCallback().closeTab(ok);
            }));
        };
    }

    // A pathway is named within its document, so neither half identifies a tab on its own.
    private static String key(final PathwaysDoc pathwaysDoc, final String name) {
        return pathwaysDoc.getUuid() + "|" + name;
    }
}
