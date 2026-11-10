package gg.guab.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerResolverTest {

    private static PlayerResolver.Match online(String name) {
        return new PlayerResolver.Match(UUID.randomUUID(), name, true);
    }

    private static PlayerResolver.Match offline(String name) {
        return new PlayerResolver.Match(UUID.randomUUID(), name, false);
    }

    @Test
    void exactOnlineNameWinsOverPrefix() {
        PlayerResolver.Match alice = online("Alice");
        PlayerResolver.Match aliceSmith = online("AliceSmith");

        assertEquals(alice, PlayerResolver.pick("Alice", List.of(alice, aliceSmith)));
    }

    @Test
    void onlineNamesAreCaseInsensitive() {
        PlayerResolver.Match alice = online("Alice");

        assertEquals(alice, PlayerResolver.pick("aLiCe", List.of(alice)));
    }

    @Test
    void uniqueOnlinePrefixResolves() {
        PlayerResolver.Match alice = online("Alice");
        PlayerResolver.Match bob = online("Bob");

        assertEquals(alice, PlayerResolver.pick("ali", List.of(alice, bob)));
    }

    @Test
    void ambiguousOnlinePrefixIsRejected() {
        List<PlayerResolver.Match> matches = List.of(online("Alice"), online("Alina"));

        assertNull(PlayerResolver.pick("ali", matches));
    }

    @Test
    void knownOfflineNameResolves() {
        PlayerResolver.Match offline = offline("Charlie");

        assertEquals(offline, PlayerResolver.pick("Charlie", List.of(offline)));
    }

    @Test
    void offlineExactBeatsOnlinePrefix() {
        PlayerResolver.Match offlineBob = offline("Bob");
        PlayerResolver.Match onlineBobby = online("Bobby");

        assertEquals(offlineBob, PlayerResolver.pick("Bob", List.of(onlineBobby, offlineBob)));
    }

    @Test
    void unknownNameIsRejected() {
        assertNull(PlayerResolver.pick("Nobody", List.of(online("Alice"), offline("Bob"))));
    }

    @Test
    void uuidLookupOnlyAcceptsKnownPlayers() {
        PlayerResolver.Match alice = online("Alice");
        assertEquals(alice, PlayerResolver.pick(alice.id().toString(), List.of(alice)));
        assertNull(PlayerResolver.pick(UUID.randomUUID().toString(), List.of(alice)));
    }
}
