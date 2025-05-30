/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.receive.content.client.view;

import stroom.receive.content.client.presenter.ContentTemplateTabPresenter.ContentTemplateTabView;
import stroom.util.shared.NullSafe;
import stroom.widget.form.client.FormGroup;
import stroom.widget.util.client.SafeHtmlUtil;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

public class ContentTemplateTabViewImpl extends ViewImpl implements ContentTemplateTabView {

    private final Widget widget;

    @UiField
    SimplePanel table;
    @UiField
    FormGroup descriptionFormGroup;
    @UiField
    HTML description;
    @UiField
    FormGroup expressionFormGroup;
    @UiField
    SimplePanel expression;

    @Inject
    public ContentTemplateTabViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        descriptionFormGroup.setVisible(false);
        expressionFormGroup.setVisible(false);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setTableView(final View view) {
        this.table.setWidget(view.asWidget());
    }

    @Override
    public void setDescription(final String description) {
        if (NullSafe.isBlankString(description)) {
            this.description.setHTML(SafeHtmlUtils.EMPTY_SAFE_HTML);
            this.descriptionFormGroup.setVisible(false);
        } else {
            this.description.setHTML(SafeHtmlUtil.toParagraphs(description));
            this.descriptionFormGroup.setVisible(true);
        }
    }

    @Override
    public void setExpressionView(final View view) {
        if (view != null) {
            this.expressionFormGroup.setVisible(true);
            this.expression.setWidget(view.asWidget());
        } else {
            this.expressionFormGroup.setVisible(false);
            this.expression.setWidget(null);
        }
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ContentTemplateTabViewImpl> {

    }
}
