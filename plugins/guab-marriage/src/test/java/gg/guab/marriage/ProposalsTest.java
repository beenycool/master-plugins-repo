package gg.guab.marriage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalsTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final long TIMEOUT = 1_000L;

    private final Proposals proposals = new Proposals(TIMEOUT);

    @Test
    void proposeCreatesOnePendingProposal() {
        assertTrue(proposals.propose(ALICE, "Alice", BOB, "Bob", 5_000L));

        Proposals.Proposal found = proposals.find(BOB, ALICE, 5_000L);
        assertNotNull(found);
        assertEquals("Alice", found.proposerName());
        assertEquals("Bob", found.targetName());
        assertEquals(1, proposals.pendingFor(BOB, 5_000L).size());
        assertNotNull(proposals.findByName(BOB, "alice", 5_000L));
    }

    @Test
    void duplicateLiveProposalIsRejectedAndExpiredOneIsReplaced() {
        assertTrue(proposals.propose(ALICE, "Alice", BOB, "Bob", 5_000L));
        assertFalse(proposals.propose(ALICE, "Alice", BOB, "Bob", 5_999L));

        // At exactly the timeout boundary the original is gone and a new one is allowed.
        assertTrue(proposals.propose(ALICE, "Alice", BOB, "Bob", 6_000L));
        assertEquals(1, proposals.pendingFor(BOB, 6_000L).size());
    }

    @Test
    void selfProposalIsRejected() {
        assertFalse(proposals.propose(ALICE, "Alice", ALICE, "Alice", 1_000L));
        assertTrue(proposals.pendingFor(ALICE, 1_000L).isEmpty());
    }

    @Test
    void expiryBoundaryIsExact() {
        assertTrue(proposals.propose(ALICE, "Alice", BOB, "Bob", 1_000L));

        assertNotNull(proposals.find(BOB, ALICE, 1_999L));
        assertNull(proposals.find(BOB, ALICE, 2_000L));
        assertTrue(proposals.pendingFor(BOB, 2_000L).isEmpty());
    }

    @Test
    void removeWorksPerProposalAndClearDropsBothDirections() {
        assertTrue(proposals.propose(ALICE, "Alice", BOB, "Bob", 1_000L));
        assertTrue(proposals.propose(CAROL, "Carol", BOB, "Bob", 1_000L));
        assertEquals(2, proposals.pendingFor(BOB, 1_000L).size());

        assertNotNull(proposals.remove(BOB, ALICE));
        assertEquals(1, proposals.pendingFor(BOB, 1_000L).size());

        assertTrue(proposals.propose(ALICE, "Alice", CAROL, "Carol", 1_000L));
        proposals.clear(CAROL);
        assertEquals(0, proposals.size(1_000L));

        assertTrue(proposals.propose(ALICE, "Alice", BOB, "Bob", 1_000L));
        proposals.clear(ALICE);
        assertEquals(0, proposals.size(1_000L));
    }
}
