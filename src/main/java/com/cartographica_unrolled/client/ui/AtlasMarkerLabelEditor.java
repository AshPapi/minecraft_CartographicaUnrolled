package com.cartographica_unrolled.client.ui;

import com.cartographica_unrolled.data.MapMarker;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import org.jspecify.annotations.Nullable;

public final class AtlasMarkerLabelEditor {
    private static final int MAX_LABEL_CODEPOINTS = 32;

    private @Nullable MapMarker editingMarker;
    private int cursorPos;
    private String editingOriginalLabel = "";
    private boolean editingNewlyCreated;

    public @Nullable MapMarker editingMarker() {
        return this.editingMarker;
    }

    public int cursorPos() {
        return this.cursorPos;
    }

    public boolean isEditing() {
        return this.editingMarker != null;
    }

    public void onScreenInit(Screen screen, @Nullable Minecraft mc) {
        if (this.editingMarker != null && mc != null) {
            mc.textInputManager().startTextInput(screen);
        }
    }

    public void abortOnDisconnect(Screen screen, @Nullable Minecraft mc) {
        this.editingMarker = null;
        this.editingOriginalLabel = "";
        this.editingNewlyCreated = false;
        this.cursorPos = 0;
        if (mc != null) {
            mc.textInputManager().stopTextInput(screen);
        }
    }

    public void onTick(Screen screen, @Nullable Minecraft mc) {
        if (this.editingMarker == null) {
            return;
        }
        MapMarker current = AtlasEditorState.findMarkerById(this.editingMarker.getId());
        if (current == null) {
            abortOnDisconnect(screen, mc);
        } else if (current != this.editingMarker) {
            current.setLabel(this.editingMarker.getLabel());
            this.editingMarker = current;
        }
    }

    public void beginEditing(Screen screen, @Nullable Minecraft mc, MapMarker marker, boolean newlyCreated) {
        if (this.editingMarker != null && this.editingMarker != marker) {
            commit(screen, mc);
        }
        this.editingMarker = marker;
        this.editingOriginalLabel = marker.getLabel();
        this.editingNewlyCreated = newlyCreated;
        this.cursorPos = marker.getLabel().length();
        if (mc != null) {
            mc.textInputManager().startTextInput(screen);
        }
    }

    public void commit(Screen screen, @Nullable Minecraft mc) {
        if (this.editingMarker != null) {
            String stripped = this.editingMarker.getLabel().stripTrailing();
            if (!stripped.equals(this.editingMarker.getLabel())) {
                this.editingMarker.setLabel(stripped);
            }
            if (!stripped.equals(this.editingOriginalLabel)) {
                if (!this.editingNewlyCreated) {
                    this.editingMarker.setLabel(this.editingOriginalLabel);
                    AtlasEditorState.pushUndoSnapshot();
                    this.editingMarker.setLabel(stripped);
                }
                AtlasEditorState.syncMarker(this.editingMarker);
            }
            this.editingMarker = null;
            this.editingOriginalLabel = "";
            this.editingNewlyCreated = false;
            this.cursorPos = 0;
        }
        if (mc != null) {
            mc.textInputManager().stopTextInput(screen);
        }
    }

    public boolean charTyped(CharacterEvent event) {
        if (this.editingMarker == null) {
            return false;
        }
        if (event.isAllowedChatCharacter()) {
            insertTextAtCursor(event.codepointAsString());
        }
        return true;
    }

    public boolean handleKeyPressed(Screen screen, @Nullable Minecraft mc, KeyEvent event) {
        if (this.editingMarker == null) {
            return false;
        }
        if (event.isConfirmation() || event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            commit(screen, mc);
            return true;
        }
        if (event.isEscape() || event.key() == InputConstants.KEY_ESCAPE) {
            commit(screen, mc);
            return true;
        }
        if (event.isPaste() && mc != null) {
            insertTextAtCursor(mc.keyboardHandler.getClipboard());
            return true;
        }

        String cur = this.editingMarker.getLabel();
        int pos = Mth.clamp(this.cursorPos, 0, cur.length());

        if (event.key() == InputConstants.KEY_BACKSPACE) {
            if (pos > 0) {
                int prevPos = Character.offsetByCodePoints(cur, pos, -1);
                this.editingMarker.setLabel(cur.substring(0, prevPos) + cur.substring(pos));
                this.cursorPos = prevPos;
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_DELETE) {
            if (pos < cur.length()) {
                int nextPos = Character.offsetByCodePoints(cur, pos, 1);
                this.editingMarker.setLabel(cur.substring(0, pos) + cur.substring(nextPos));
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_LEFT) {
            if (pos > 0) {
                this.cursorPos = Character.offsetByCodePoints(cur, pos, -1);
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_RIGHT) {
            if (pos < cur.length()) {
                this.cursorPos = Character.offsetByCodePoints(cur, pos, 1);
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_HOME) {
            this.cursorPos = 0;
            return true;
        }
        if (event.key() == InputConstants.KEY_END) {
            this.cursorPos = cur.length();
            return true;
        }
        return true;
    }

    private void insertTextAtCursor(String rawInsert) {
        if (this.editingMarker == null || rawInsert.isEmpty()) {
            return;
        }
        String current = this.editingMarker.getLabel();
        int curCodePoints = current.codePointCount(0, current.length());
        int remaining = MAX_LABEL_CODEPOINTS - curCodePoints;
        if (remaining <= 0) {
            return;
        }

        StringBuilder filtered = new StringBuilder();
        int added = 0;
        for (int i = 0; i < rawInsert.length() && added < remaining; ) {
            int cp = rawInsert.codePointAt(i);
            i += Character.charCount(cp);
            if (StringUtil.isAllowedChatCharacter(cp)) {
                filtered.appendCodePoint(cp);
                added++;
            }
        }
        if (filtered.isEmpty()) {
            return;
        }

        int pos = Mth.clamp(this.cursorPos, 0, current.length());
        String next = current.substring(0, pos) + filtered + current.substring(pos);
        this.editingMarker.setLabel(next);
        this.cursorPos = pos + filtered.length();
    }
}
