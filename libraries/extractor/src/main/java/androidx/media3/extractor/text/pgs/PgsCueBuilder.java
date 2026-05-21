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

import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import java.util.ArrayList;
import java.util.Arrays;

/** Builds PGS cues from presentation, palette, and bitmap sections. */
/* package */ final class PgsCueBuilder {

  private static final int MAX_COMPOSITION_OBJECTS = 2;
  private static final int MAX_WINDOW_DEFINITIONS = 2;
  private static final int PALETTE_ENTRY_SIZE = 5;
  private static final int PALETTE_HEADER_SIZE = 2;
  private static final int BITMAP_SECTION_HEADER_SIZE = 4;
  private static final int BITMAP_BASE_SECTION_HEADER_SIZE = 7;
  private static final int BITMAP_DIMENSION_SIZE = 4;
  private static final int IDENTIFIER_SECTION_HEADER_SIZE = 11;
  private static final int COMPOSITION_STATE_MASK = 0xC0;
  private static final int COMPOSITION_OBJECT_SIZE = 8;
  private static final int COMPOSITION_OBJECT_CROP_SIZE = 8;
  private static final int COMPOSITION_OBJECT_CROPPED_FLAG = 0x80;
  private static final int WINDOW_DEFINITION_HEADER_SIZE = 1;
  private static final int WINDOW_DEFINITION_SIZE = 9;
  private static final int OBJECT_DATA_SEQUENCE_FIRST = 0x80;
  private static final int RLE_SHORT_RUN_LENGTH_MASK = 0x3F;
  private static final int RLE_SWITCH_LONG_RUN = 0x40;
  private static final int RLE_SWITCH_HAS_COLOR = 0x80;
  private static final int PALETTE_UPDATE_FLAG = 0x80;
  private static final int INVALID_SECTION_LENGTH = -1;
  private static final int INVALID_PIXEL_COUNT = -1;
  private static final int INVALID_RUN_END_INDEX = -1;

  private final PgsEpochState epochState;
  private final PgsCompositionObject[] compositionObjects;
  private final PgsWindowDefinition[] windowDefinitions;
  private final ParsableByteArray bitmapDataReader;
  private int[] argbBitmapData;
  private int planeWidth;
  private int planeHeight;
  private int paletteId;
  private boolean hasPresentation;
  private boolean paletteUpdateOnly;
  private int compositionObjectCount;
  private int windowDefinitionCount;

  PgsCueBuilder() {
    epochState = new PgsEpochState();
    bitmapDataReader = new ParsableByteArray();
    compositionObjects = new PgsCompositionObject[MAX_COMPOSITION_OBJECTS];
    for (int i = 0; i < compositionObjects.length; i++) {
      compositionObjects[i] = new PgsCompositionObject();
    }
    windowDefinitions = new PgsWindowDefinition[MAX_WINDOW_DEFINITIONS];
    for (int i = 0; i < windowDefinitions.length; i++) {
      windowDefinitions[i] = new PgsWindowDefinition();
    }
    argbBitmapData = new int[0];
    paletteId = C.INDEX_UNSET;
  }

  private static int yCrCbToArgb(int y, int cr, int cb, int a) {
    int r = (int) (y + (1.40200 * (cr - 128)));
    int g = (int) (y - (0.34414 * (cb - 128)) - (0.71414 * (cr - 128)));
    int b = (int) (y + (1.77200 * (cb - 128)));
    return (a << 24)
        | (Util.constrainValue(r, 0, 255) << 16)
        | (Util.constrainValue(g, 0, 255) << 8)
        | Util.constrainValue(b, 0, 255);
  }

  private static int readRunLength(ParsableByteArray bitmapData, int switchBits) {
    int runLength = switchBits & RLE_SHORT_RUN_LENGTH_MASK;
    if ((switchBits & RLE_SWITCH_LONG_RUN) == 0) {
      return runLength;
    }
    int lowByte = readRleByte(bitmapData);
    return lowByte == C.INDEX_UNSET ? C.INDEX_UNSET : (runLength << 8) | lowByte;
  }

  private static int readRunColorIndex(ParsableByteArray bitmapData, int switchBits) {
    if ((switchBits & RLE_SWITCH_HAS_COLOR) == 0) {
      return 0;
    }
    return readRleByte(bitmapData);
  }

  private static int readRleByte(ParsableByteArray bitmapData) {
    if (bitmapData.bytesLeft() == 0) {
      return C.INDEX_UNSET;
    }
    return bitmapData.readUnsignedByte();
  }

  void parsePaletteSection(ParsableByteArray buffer, int sectionLength) {
    if ((sectionLength % PALETTE_ENTRY_SIZE) != PALETTE_HEADER_SIZE) {
      return;
    }
    int id = buffer.readUnsignedByte();
    PgsPalette palette = epochState.getOrCreatePalette(id);
    if (palette == null) {
      return;
    }
    buffer.skipBytes(1);

    if (!paletteUpdateOnly) {
      Arrays.fill(palette.colors, 0);
    }
    int entryCount = sectionLength / PALETTE_ENTRY_SIZE;
    for (int i = 0; i < entryCount; i++) {
      int index = buffer.readUnsignedByte();
      int y = buffer.readUnsignedByte();
      int cr = buffer.readUnsignedByte();
      int cb = buffer.readUnsignedByte();
      int a = buffer.readUnsignedByte();
      palette.colors[index] = yCrCbToArgb(y, cr, cb, a);
    }
  }

  void parseBitmapSection(ParsableByteArray buffer, int sectionLength) {
    if (sectionLength < BITMAP_SECTION_HEADER_SIZE) {
      return;
    }
    int objectId = buffer.readUnsignedShort();
    buffer.skipBytes(1);
    boolean isBaseSection = (OBJECT_DATA_SEQUENCE_FIRST & buffer.readUnsignedByte()) != 0;
    sectionLength -= BITMAP_SECTION_HEADER_SIZE;

    PgsBitmapObject bitmapObject =
        isBaseSection
            ? epochState.getOrCreateBitmapObject(objectId)
            : epochState.findBitmapObject(objectId);
    if (bitmapObject == null) {
      return;
    }
    if (isBaseSection) {
      sectionLength = parseBitmapBaseSection(buffer, bitmapObject, sectionLength);
      if (sectionLength == INVALID_SECTION_LENGTH) {
        bitmapObject.resetData();
        return;
      }
    }

    if (!bitmapObject.appendRleData(buffer, sectionLength)) {
      bitmapObject.resetData();
    }
  }

  private int parseBitmapBaseSection(
      ParsableByteArray buffer, PgsBitmapObject bitmapObject, int sectionLength) {
    if (sectionLength < BITMAP_BASE_SECTION_HEADER_SIZE) {
      return INVALID_SECTION_LENGTH;
    }
    int totalLength = buffer.readUnsignedInt24();
    if (totalLength < BITMAP_DIMENSION_SIZE) {
      return INVALID_SECTION_LENGTH;
    }
    int bitmapWidth = buffer.readUnsignedShort();
    int bitmapHeight = buffer.readUnsignedShort();
    if (bitmapWidth == 0
        || bitmapHeight == 0
        || bitmapWidth > planeWidth
        || bitmapHeight > planeHeight) {
      return INVALID_SECTION_LENGTH;
    }
    int rleDataLength = totalLength - BITMAP_DIMENSION_SIZE;
    int remainingSectionLength = sectionLength - BITMAP_BASE_SECTION_HEADER_SIZE;
    if (remainingSectionLength > rleDataLength) {
      return INVALID_SECTION_LENGTH;
    }
    bitmapObject.resetForBaseSection(bitmapWidth, bitmapHeight, rleDataLength);
    return sectionLength - BITMAP_BASE_SECTION_HEADER_SIZE;
  }

  void parseIdentifierSection(ParsableByteArray buffer, int sectionLength) {
    if (sectionLength < IDENTIFIER_SECTION_HEADER_SIZE) {
      return;
    }
    int newPlaneWidth = buffer.readUnsignedShort();
    int newPlaneHeight = buffer.readUnsignedShort();
    buffer.skipBytes(3);
    int compositionState = buffer.readUnsignedByte();
    paletteUpdateOnly = (buffer.readUnsignedByte() & PALETTE_UPDATE_FLAG) != 0;
    int newPaletteId = buffer.readUnsignedByte();
    if ((compositionState & COMPOSITION_STATE_MASK) != 0) {
      epochState.reset();
      paletteUpdateOnly = false;
    }
    if (newPlaneWidth == 0 || newPlaneHeight == 0) {
      clearPresentation();
      planeWidth = 0;
      planeHeight = 0;
      paletteId = C.INDEX_UNSET;
      return;
    }
    planeWidth = newPlaneWidth;
    planeHeight = newPlaneHeight;
    paletteId = newPaletteId;
    hasPresentation = true;
    int numObjects = buffer.readUnsignedByte();
    parseCompositionObjects(buffer, numObjects, sectionLength - IDENTIFIER_SECTION_HEADER_SIZE);
  }

  private void parseCompositionObjects(ParsableByteArray buffer, int numObjects, int remaining) {
    clearCompositionObjects();
    for (int i = 0; i < numObjects && remaining >= COMPOSITION_OBJECT_SIZE; i++) {
      int nextRemaining = parseCompositionObject(buffer, remaining);
      if (nextRemaining == INVALID_SECTION_LENGTH) {
        break;
      }
      remaining = nextRemaining;
    }
  }

  private int parseCompositionObject(ParsableByteArray buffer, int remaining) {
    int objectId = buffer.readUnsignedShort();
    int windowId = buffer.readUnsignedByte();
    int compositionFlag = buffer.readUnsignedByte();
    int x = buffer.readUnsignedShort();
    int y = buffer.readUnsignedShort();
    remaining -= COMPOSITION_OBJECT_SIZE;
    if ((compositionFlag & COMPOSITION_OBJECT_CROPPED_FLAG) != 0) {
      remaining = skipCompositionObjectCrop(buffer, remaining);
      if (remaining == INVALID_SECTION_LENGTH) {
        return INVALID_SECTION_LENGTH;
      }
    }
    maybeStoreCompositionObject(objectId, windowId, x, y);
    return remaining;
  }

  private int skipCompositionObjectCrop(ParsableByteArray buffer, int remaining) {
    if (remaining < COMPOSITION_OBJECT_CROP_SIZE) {
      return INVALID_SECTION_LENGTH;
    }
    buffer.skipBytes(COMPOSITION_OBJECT_CROP_SIZE);
    return remaining - COMPOSITION_OBJECT_CROP_SIZE;
  }

  private void maybeStoreCompositionObject(int objectId, int windowId, int x, int y) {
    if (compositionObjectCount < MAX_COMPOSITION_OBJECTS && isPositionInsidePlane(x, y)) {
      compositionObjects[compositionObjectCount++].set(objectId, windowId, x, y);
    }
  }

  void parseWindowDefinitionSection(ParsableByteArray buffer, int sectionLength) {
    clearWindowDefinitions();
    if (sectionLength < WINDOW_DEFINITION_HEADER_SIZE) {
      return;
    }
    int numWindows = buffer.readUnsignedByte();
    int remaining = sectionLength - WINDOW_DEFINITION_HEADER_SIZE;
    for (int i = 0; i < numWindows && remaining >= WINDOW_DEFINITION_SIZE; i++) {
      remaining = parseWindowDefinition(buffer, remaining);
    }
  }

  private int parseWindowDefinition(ParsableByteArray buffer, int remaining) {
    int id = buffer.readUnsignedByte();
    int x = buffer.readUnsignedShort();
    int y = buffer.readUnsignedShort();
    int width = buffer.readUnsignedShort();
    int height = buffer.readUnsignedShort();
    // Window definitions are kept for display-set compatibility. Cue positioning still uses the
    // composition object's x/y coordinates, which carry the actual bitmap origin.
    maybeStoreWindowDefinition(id, x, y, width, height);
    return remaining - WINDOW_DEFINITION_SIZE;
  }

  private void maybeStoreWindowDefinition(int id, int x, int y, int width, int height) {
    if (windowDefinitionCount < MAX_WINDOW_DEFINITIONS
        && isValidWindowDefinition(x, y, width, height)) {
      windowDefinitions[windowDefinitionCount++].set(id, x, y, width, height);
    }
  }

  private boolean isValidWindowDefinition(int x, int y, int width, int height) {
    return width != 0
        && height != 0
        && isPositionInsidePlane(x, y)
        && width <= planeWidth - x
        && height <= planeHeight - y;
  }

  private boolean isPositionInsidePlane(int x, int y) {
    return x < planeWidth && y < planeHeight;
  }

  void build(ArrayList<Cue> cues) {
    if (!hasPresentation) {
      return;
    }
    PgsPalette palette = epochState.findPalette(paletteId);
    if (palette == null) {
      return;
    }
    for (int i = 0; i < compositionObjectCount; i++) {
      PgsCompositionObject compositionObject = compositionObjects[i];
      PgsBitmapObject bitmapObject = epochState.findBitmapObject(compositionObject.objectId);
      if (bitmapObject == null || !bitmapObject.isComplete()) {
        continue;
      }
      Cue cue = buildCue(bitmapObject, palette.colors, compositionObject.x, compositionObject.y);
      if (cue != null) {
        cues.add(cue);
      }
    }
  }

  boolean isClearDisplaySet() {
    return hasPresentation && compositionObjectCount == 0;
  }

  void clearPresentation() {
    hasPresentation = false;
    paletteUpdateOnly = false;
    clearCompositionObjects();
    clearWindowDefinitions();
  }

  private void clearCompositionObjects() {
    for (int i = 0; i < compositionObjectCount; i++) {
      compositionObjects[i].clear();
    }
    compositionObjectCount = 0;
  }

  private void clearWindowDefinitions() {
    for (int i = 0; i < windowDefinitionCount; i++) {
      windowDefinitions[i].clear();
    }
    windowDefinitionCount = 0;
  }

  void reset() {
    clearPresentation();
    planeWidth = 0;
    planeHeight = 0;
    paletteId = C.INDEX_UNSET;
    epochState.reset();
  }

  @Nullable
  private Cue buildCue(PgsBitmapObject bitmapObject, int[] colors, int x, int y) {
    int decodedPixelCount = decodeBitmapData(bitmapObject, colors);
    if (decodedPixelCount == INVALID_PIXEL_COUNT) {
      return null;
    }
    Bitmap bitmap =
        Bitmap.createBitmap(
            argbBitmapData, bitmapObject.width(), bitmapObject.height(), Bitmap.Config.ARGB_8888);
    return createCue(bitmap, bitmapObject.width(), x, y);
  }

  private int decodeBitmapData(PgsBitmapObject bitmapObject, int[] colors) {
    long pixelCountLong = (long) bitmapObject.width() * bitmapObject.height();
    if (pixelCountLong > Integer.MAX_VALUE) {
      return INVALID_PIXEL_COUNT;
    }
    int pixelCount = (int) pixelCountLong;
    if (argbBitmapData.length < pixelCount) {
      argbBitmapData = new int[pixelCount];
    }
    bitmapObject.resetReader(bitmapDataReader);
    int index = 0;
    while (index < pixelCount) {
      if (bitmapDataReader.bytesLeft() == 0) {
        return INVALID_PIXEL_COUNT;
      }
      int colorIndex = bitmapDataReader.readUnsignedByte();
      if (colorIndex != 0) {
        argbBitmapData[index++] = colors[colorIndex];
      } else {
        int nextIndex = decodeRunLength(bitmapDataReader, colors, pixelCount, index);
        if (nextIndex == INVALID_RUN_END_INDEX) {
          return INVALID_PIXEL_COUNT;
        }
        index = nextIndex;
      }
    }
    return pixelCount;
  }

  private Cue createCue(Bitmap bitmap, int bitmapWidth, int x, int y) {
    return new Cue.Builder()
        .setBitmap(bitmap)
        .setPosition((float) x / planeWidth)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine((float) y / planeHeight, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize((float) bitmapWidth / planeWidth)
        .build();
  }

  private int decodeRunLength(
      ParsableByteArray bitmapData, int[] colors, int pixelCount, int index) {
    if (bitmapData.bytesLeft() == 0) {
      return INVALID_RUN_END_INDEX;
    }
    int switchBits = bitmapData.readUnsignedByte();
    if (switchBits == 0) {
      return index;
    }
    int runLength = readRunLength(bitmapData, switchBits);
    if (runLength == C.INDEX_UNSET || runLength > pixelCount - index) {
      return INVALID_RUN_END_INDEX;
    }
    int runColorIndex = readRunColorIndex(bitmapData, switchBits);
    if (runColorIndex == C.INDEX_UNSET) {
      return INVALID_RUN_END_INDEX;
    }
    Arrays.fill(argbBitmapData, index, index + runLength, colors[runColorIndex]);
    return index + runLength;
  }
}
