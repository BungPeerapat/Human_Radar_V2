package com.example.radarhumanapplication.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class RecordingFileTest {

    private static JsonObject sampleFrame(int idx) {
        JsonObject t1 = new JsonObject();
        t1.addProperty("x", idx * 10);
        t1.addProperty("y", idx * 20);
        t1.addProperty("s", 0);
        t1.addProperty("d", idx * 100);
        t1.addProperty("p", true);

        JsonArray arr = new JsonArray();
        arr.add(t1);

        JsonObject line = new JsonObject();
        line.addProperty("t", 1_700_000_000_000L + idx * 100L);
        line.add("targets", arr);
        return line;
    }

    @Test
    public void writeAndReadFiveFramesRoundtrip() throws IOException {
        File dir = Files.createTempDirectory("rec_test").toFile();
        File f = new File(dir, "test.jsonl");

        List<JsonObject> written = new ArrayList<>();
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8))) {
            for (int i = 0; i < 5; i++) {
                JsonObject frame = sampleFrame(i);
                written.add(frame);
                w.write(frame.toString());
                w.newLine();
            }
        }

        List<RecordingFile.Frame> read = RecordingFile.read(f);
        assertEquals(5, read.size());
        for (int i = 0; i < 5; i++) {
            JsonObject orig = written.get(i);
            assertEquals(orig.get("t").getAsLong(), read.get(i).timestampMs);
            assertNotNull(read.get(i).targets);
            // Compare serialized targets array — JsonArray equality is structural.
            assertEquals(orig.get("targets").toString(), read.get(i).targets.toString());
        }

        assertTrue(f.delete());
        assertTrue(dir.delete());
    }

    @Test
    public void readSkipsBlankLines() throws IOException {
        File dir = Files.createTempDirectory("rec_test2").toFile();
        File f = new File(dir, "blank.jsonl");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8))) {
            w.write(sampleFrame(0).toString());
            w.newLine();
            w.write("");
            w.newLine();
            w.write("   ");
            w.newLine();
            w.write(sampleFrame(1).toString());
            w.newLine();
        }
        List<RecordingFile.Frame> frames = RecordingFile.read(f);
        assertEquals(2, frames.size());
        f.delete();
        dir.delete();
    }
}
