package com.example.radarhumanapplication.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class FirmwareManifestTest {
    private FirmwareManifest valid() {
        FirmwareManifest m = new FirmwareManifest();
        m.versionName = "1.0.99";
        m.binUrl = "https://github.com/example/releases/download/v1/firmware.bin";
        m.sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        m.sizeBytes = 1000000;
        return m;
    }

    @Test public void validHttpsManifestIsUsable() { assertTrue(valid().isUsable()); }
    @Test public void missingChecksumCannotStartOta() {
        FirmwareManifest m = valid(); m.sha256 = ""; assertFalse(m.isUsable());
        m.sha256 = null; assertFalse(m.isUsable());
    }
    @Test public void malformedChecksumCannotStartOta() {
        FirmwareManifest m = valid(); m.sha256 = "f"; assertFalse(m.isUsable());
        m.sha256 = new String(new char[64]).replace('\0', 'z'); assertFalse(m.isUsable());
    }
    @Test public void plaintextOrCredentialUrlCannotStartOta() {
        FirmwareManifest m = valid(); m.binUrl = "http://example.com/fw.bin"; assertFalse(m.isUsable());
        m.binUrl = "https://user:password@example.com/fw.bin"; assertFalse(m.isUsable());
        m.binUrl = "https:///fw.bin"; assertFalse(m.isUsable());
    }
    @Test public void missingOrOversizedImageCannotStartOta() {
        FirmwareManifest m = valid(); m.sizeBytes = 0; assertFalse(m.isUsable());
        m.sizeBytes = -1; assertFalse(m.isUsable());
        m.sizeBytes = 16L * 1024 * 1024 + 1; assertFalse(m.isUsable());
    }
}
