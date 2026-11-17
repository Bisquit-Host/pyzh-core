package net.minecraft.server.players;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.notifications.NotificationService;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@LeafTest
public class StoredUserListSaveTest {
    @TempDir Path directory;

    @Test
    void mutationsFinishWritingBeforeReload() throws IOException {
        Path file = this.directory.resolve("users.json");
        TestList list = new TestList(file.toFile());
        list.add(new Entry("first"));
        assertTrue(Files.readString(file).contains("first"));
        list.add(new Entry("second"));
        list.remove("first");

        TestList reloaded = new TestList(file.toFile());
        reloaded.load();
        assertArrayEquals(new String[] {"second"}, reloaded.getUserList());
        list.clear();
        reloaded.load();
        assertTrue(reloaded.isEmpty());
    }

    @Test
    void savePropagatesWriteFailures() {
        TestList list = new TestList(this.directory.resolve("missing/users.json").toFile());
        assertThrows(IOException.class, list::save);
    }

    private static final class TestList extends StoredUserList<String, Entry> {
        private TestList(File file) {
            super(file, mock(NotificationService.class));
        }

        @Override
        protected Entry createEntry(JsonObject object) {
            return new Entry(object.get("name").getAsString());
        }
    }

    private static final class Entry extends StoredUserEntry<String> {
        private Entry(String user) {
            super(user);
        }

        @Override
        protected void serialize(JsonObject object) {
            object.addProperty("name", this.getUser());
        }
    }
}
