/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.text.pgs;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser.OutputOptions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;

/** Accumulates raw SUP display sets so each cue can use the next display set as its end time. */
/* package */ final class PgsSupTiming {

  private final OutputOptions outputOptions;
  private final Consumer<CuesWithTiming> output;
  @Nullable
  private final ArrayList<CuesWithTiming> cuesBeforeRequestedStartTimeUs;

  @Nullable
  private ImmutableList<Cue> pendingCues;
  private long pendingStartTimeUs;

  PgsSupTiming(OutputOptions outputOptions, Consumer<CuesWithTiming> output) {
    this.outputOptions = outputOptions;
    this.output = output;
    cuesBeforeRequestedStartTimeUs =
        outputOptions.startTimeUs != C.TIME_UNSET && outputOptions.outputAllCues
            ? new ArrayList<>()
            : null;
    pendingStartTimeUs = C.TIME_UNSET;
  }

  void onDisplaySet(ArrayList<Cue> displaySetCues, long startTimeUs) {
    emitPendingCues(/* nextStartTimeUs= */ startTimeUs);
    pendingCues = ImmutableList.copyOf(displaySetCues);
    pendingStartTimeUs = startTimeUs;
  }

  void onClearDisplaySet(long startTimeUs) {
    emitPendingCues(/* nextStartTimeUs= */ startTimeUs);
    pendingCues = null;
    pendingStartTimeUs = C.TIME_UNSET;
  }

  void finish() {
    emitPendingCues(/* nextStartTimeUs= */ C.TIME_UNSET);
    if (cuesBeforeRequestedStartTimeUs != null) {
      for (int i = 0; i < cuesBeforeRequestedStartTimeUs.size(); i++) {
        output.accept(cuesBeforeRequestedStartTimeUs.get(i));
      }
    }
  }

  private void emitPendingCues(long nextStartTimeUs) {
    if (pendingCues == null) {
      return;
    }
    long durationUs =
        nextStartTimeUs != C.TIME_UNSET && nextStartTimeUs > pendingStartTimeUs
            ? nextStartTimeUs - pendingStartTimeUs
            : C.TIME_UNSET;
    emitCues(new CuesWithTiming(pendingCues, pendingStartTimeUs, durationUs));
    pendingCues = null;
    pendingStartTimeUs = C.TIME_UNSET;
  }

  private void emitCues(CuesWithTiming cuesWithTiming) {
    if (outputOptions.startTimeUs == C.TIME_UNSET
        || cuesWithTiming.endTimeUs == C.TIME_UNSET
        || cuesWithTiming.endTimeUs >= outputOptions.startTimeUs) {
      output.accept(cuesWithTiming);
    } else if (cuesBeforeRequestedStartTimeUs != null) {
      cuesBeforeRequestedStartTimeUs.add(cuesWithTiming);
    }
  }
}
