/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.smoothstreaming;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest;
import androidx.media3.exoplayer.source.chunk.ChunkSource;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.LoaderErrorThrower;
import androidx.media3.extractor.Extractor;

/** A {@link ChunkSource} for SmoothStreaming. */
@UnstableApi
public interface SsChunkSource extends ChunkSource {

  /** Factory for {@link SsChunkSource}s. */
  interface Factory {

    /**
     * Creates a new {@link SsChunkSource}.
     *
     * @param manifestLoaderErrorThrower Throws errors affecting loading of manifests.
     * @param manifest The initial manifest.
     * @param streamElementIndex The index of the corresponding stream element in the manifest.
     * @param trackSelection The track selection.
     * @param transferListener The transfer listener which should be informed of any data transfers.
     *     May be null if no listener is available.
     * @param cmcdConfiguration The {@link CmcdConfiguration} for this chunk source.
     * @return The created {@link SsChunkSource}.
     */
    SsChunkSource createChunkSource(
        LoaderErrorThrower manifestLoaderErrorThrower,
        SsManifest manifest,
        int streamElementIndex,
        ExoTrackSelection trackSelection,
        @Nullable TransferListener transferListener,
        @Nullable CmcdConfiguration cmcdConfiguration);

    /**
     * Returns the output {@link Format} of emitted {@linkplain C#TRACK_TYPE_TEXT text samples}
     * which were originally in {@code sourceFormat}.
     *
     * <p>In many cases, where an {@link Extractor} emits samples from the source without mutation,
     * this method simply returns {@code sourceFormat}. In other cases, such as an {@link Extractor}
     * that transcodes subtitles from the {@code sourceFormat} to {@link
     * MimeTypes#APPLICATION_MEDIA3_CUES}, the format is updated to indicate the transcoding that is
     * taking place.
     *
     * <p>Non-text source formats are always returned without mutation.
     *
     * @param sourceFormat The original text-based format.
     * @return The {@link Format} that will be associated with a {@linkplain C#TRACK_TYPE_TEXT text
     *     track}.
     */
    default Format getOutputTextFormat(Format sourceFormat) {
      return sourceFormat;
    }
  }

  /**
   * Updates the manifest.
   *
   * @param newManifest The new manifest.
   */
  void updateManifest(SsManifest newManifest);

  /**
   * Updates the track selection.
   *
   * @param trackSelection The new track selection instance. Must be equivalent to the previous one.
   */
  void updateTrackSelection(ExoTrackSelection trackSelection);
}
