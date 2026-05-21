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

/** Cached PGS bitmap objects and palettes for the current epoch. */
/* package */ final class PgsEpochState {

  private static final int MAX_EPOCH_OBJECTS = 64;
  private static final int MAX_EPOCH_PALETTES = 8;

  private final PgsBitmapObject[] bitmapObjects;
  private final PgsPalette[] palettes;
  private int bitmapObjectCount;
  private int paletteCount;

  PgsEpochState() {
    bitmapObjects = new PgsBitmapObject[MAX_EPOCH_OBJECTS];
    for (int i = 0; i < bitmapObjects.length; i++) {
      bitmapObjects[i] = new PgsBitmapObject();
    }
    palettes = new PgsPalette[MAX_EPOCH_PALETTES];
    for (int i = 0; i < palettes.length; i++) {
      palettes[i] = new PgsPalette();
    }
  }

  @Nullable
  PgsBitmapObject findBitmapObject(int objectId) {
    for (int i = 0; i < bitmapObjectCount; i++) {
      if (bitmapObjects[i].id == objectId) {
        return bitmapObjects[i];
      }
    }
    return null;
  }

  @Nullable
  PgsBitmapObject getOrCreateBitmapObject(int objectId) {
    PgsBitmapObject bitmapObject = findBitmapObject(objectId);
    if (bitmapObject != null) {
      return bitmapObject;
    }
    if (bitmapObjectCount < bitmapObjects.length) {
      bitmapObject = bitmapObjects[bitmapObjectCount++];
      bitmapObject.id = objectId;
      return bitmapObject;
    }
    return null;
  }

  @Nullable
  PgsPalette findPalette(int id) {
    for (int i = 0; i < paletteCount; i++) {
      if (palettes[i].id == id) {
        return palettes[i];
      }
    }
    return null;
  }

  @Nullable
  PgsPalette getOrCreatePalette(int id) {
    PgsPalette palette = findPalette(id);
    if (palette != null) {
      return palette;
    }
    if (paletteCount < palettes.length) {
      palette = palettes[paletteCount++];
      palette.id = id;
      return palette;
    }
    return null;
  }

  void reset() {
    for (int i = 0; i < bitmapObjectCount; i++) {
      bitmapObjects[i].reset();
    }
    bitmapObjectCount = 0;
    for (int i = 0; i < paletteCount; i++) {
      palettes[i].reset();
    }
    paletteCount = 0;
  }
}
