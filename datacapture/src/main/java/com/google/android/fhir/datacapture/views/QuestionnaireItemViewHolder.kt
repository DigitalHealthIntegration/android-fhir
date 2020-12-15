/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.views

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.datacapture.QuestionnaireViewModel
import org.hl7.fhir.r4.model.Questionnaire

interface QuestionnaireItemViewHolderFactory {
    fun create(parent: ViewGroup, viewModel: QuestionnaireViewModel): QuestionnaireItemViewHolder
}

abstract class QuestionnaireItemViewHolder(
  itemView: View,
  val viewModel: QuestionnaireViewModel
) : RecyclerView.ViewHolder(itemView) {
    abstract fun bind(questionnaireItemComponent: Questionnaire.QuestionnaireItemComponent)
}
