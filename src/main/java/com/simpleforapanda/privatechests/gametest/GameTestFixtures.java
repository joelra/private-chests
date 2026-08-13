package com.simpleforapanda.privatechests.gametest;

import com.simpleforapanda.privatechests.model.AccessMode;
import com.simpleforapanda.privatechests.model.LockRecord;
import com.simpleforapanda.privatechests.state.LockState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shared setup helpers for the protection game tests.
 */
final class GameTestFixtures {

    static final UUID ABSENT_OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private GameTestFixtures() {
    }

    static LockState lockState(GameTestHelper helper) {
        return LockState.get(helper.getLevel().getServer());
    }

    static Optional<LockRecord> lockAt(GameTestHelper helper, BlockPos containerRel) {
        return lockState(helper).getLock(helper.getLevel(), helper.absolutePos(containerRel));
    }

    /**
     * Edits the sign through the real updateSignText path, so the SignEditMixin
     * and SignEditService run exactly as they would for a player packet.
     */
    static void editSign(GameTestHelper helper, BlockPos signRel, ServerPlayer editor, String... lines) {
        List<FilteredText> text = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            text.add(FilteredText.passThrough(i < lines.length ? lines[i] : ""));
        }

        SignBlockEntity sign = helper.getBlockEntity(signRel, SignBlockEntity.class);
        sign.setAllowedPlayerEditor(editor.getUUID());
        sign.updateSignText(editor, true, text);
    }

    /**
     * Registers a lock for the container without a physical sign. The automation
     * and destruction paths only consult the lock record, so no sign is needed.
     */
    static LockRecord lockDirectly(GameTestHelper helper, BlockPos containerRel, UUID ownerUuid, String ownerName) {
        LockRecord record = new LockRecord(
            helper.getLevel().dimension(),
            ownerUuid,
            ownerName,
            helper.absolutePos(containerRel.north()),
            Set.of(helper.absolutePos(containerRel)),
            AccessMode.PRIVATE,
            Set.of()
        );
        lockState(helper).addLock(record);
        return record;
    }
}
