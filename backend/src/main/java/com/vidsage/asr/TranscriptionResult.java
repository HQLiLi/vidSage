package com.vidsage.asr;

import java.util.List;

public record TranscriptionResult(List<TranscriptionSegment> segments, String language) {
}
