/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.docref.DocRef;
import stroom.document.client.event.ChangeUiHandlers;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.pathways.client.presenter.TracesSettingsPresenter.TracesSettingsView;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.TracesDoc;
import stroom.planb.client.view.GeneralSettingsView;
import stroom.planb.client.view.PublishingSettingsView;
import stroom.planb.client.view.RetentionSettingsView;
import stroom.planb.client.view.SharedFileStoreSettingsView;
import stroom.planb.shared.HoldingAreaSettings;
import stroom.planb.shared.StateType;
import stroom.planb.shared.TraceSettings;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.time.SimpleDuration;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

/**
 * Settings tab of a {@link TracesDoc}: the trace store's own settings and the Pathways document it
 * hands its traces to. A trace store is only ever served from a shared file store, so this offers no
 * snapshot or part-transfer settings.
 */
public class TracesSettingsPresenter
        extends DocPresenter<TracesSettingsView, TracesDoc>
        implements ChangeUiHandlers {

    private final DocSelectionBoxPresenter pathwaysPresenter;

    /**
     * Held from the most recent read so that a write round-trips them. Neither has an editor — the
     * query range limit because nothing offers one yet, the compaction frequency because reclaiming
     * pages in the holding area is internal housekeeping an operator has no reason to set. Without
     * this a save would silently clear whatever was configured.
     */
    private SimpleDuration maxQueryTimeRange;
    private SimpleDuration compactionFrequency;

    @Inject
    public TracesSettingsPresenter(final EventBus eventBus,
                                   final TracesSettingsView view,
                                   final DocSelectionBoxPresenter pathwaysPresenter) {
        super(eventBus, view);
        this.pathwaysPresenter = pathwaysPresenter;
        view.setUiHandlers(this);

        // Handing traces over is a change to this store, so it is the edit right on this store that
        // authorises it. The user needs to be able to use the Pathways document they pick, hence VIEW
        // there, and may make one from the picker if they have none.
        pathwaysPresenter.setIncludedTypes(PathwaysDoc.TYPE);
        pathwaysPresenter.setRequiredPermissions(DocumentPermission.VIEW);
        pathwaysPresenter.setAllowCreate(true);
        view.setPathwaysView(pathwaysPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(pathwaysPresenter.addDataSelectionHandler(e -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final TracesDoc doc, final boolean readOnly) {
        final TraceSettings settings = doc.getSettings() instanceof final TraceSettings traceSettings
                ? traceSettings
                : new TraceSettings.Builder().build();
        maxQueryTimeRange = settings.getMaxQueryTimeRange();
        compactionFrequency = settings.getHoldingArea().getCompactionFrequency();
        getView().setMaxStoreSize(settings.getMaxStoreSize());
        getView().setMaxSpansPerTrace(settings.getMaxSpansPerTrace());
        getView().setGranularity(settings.getGranularity());
        getView().setMaxWaitForData(settings.getHoldingArea().getMaxWaitForData());
        getView().setRetention(settings.getRetention());
        getView().setSharedFileStore(settings.getSharedFileStore());
        getView().setSharedFileStoreLocked(doc.hasSharedFileStoreData());
        getView().onReadOnly(readOnly);
        pathwaysPresenter.setEnabled(!readOnly);
        pathwaysPresenter.setSelectedEntityReference(doc.getPathwaysDocRef(), true);
    }

    @Override
    protected TracesDoc onWrite(final TracesDoc doc) {
        final TraceSettings settings = new TraceSettings.Builder()
                .maxStoreSize(getView().getMaxStoreSize())
                .maxSpansPerTrace(getView().getMaxSpansPerTrace())
                .granularity(getView().getGranularity())
                .holdingArea(new HoldingAreaSettings.Builder()
                        .maxWaitForData(getView().getMaxWaitForData())
                        .compactionFrequency(compactionFrequency)
                        .build())
                .retention(getView().getRetention())
                .sharedFileStore(getView().getSharedFileStore())
                .maxQueryTimeRange(maxQueryTimeRange)
                .build();
        return doc.copyTraces()
                .stateType(StateType.TRACE)
                .settings(settings)
                .pathwaysDocRef(pathwaysPresenter.getSelectedEntityReference())
                .build();
    }

    public interface TracesSettingsView extends
            View,
            GeneralSettingsView,
            SharedFileStoreSettingsView,
            PublishingSettingsView,
            RetentionSettingsView,
            ReadOnlyChangeHandler,
            HasUiHandlers<ChangeUiHandlers> {

        Long getMaxSpansPerTrace();

        void setMaxSpansPerTrace(Long maxSpansPerTrace);

        void setPathwaysView(View view);
    }
}
