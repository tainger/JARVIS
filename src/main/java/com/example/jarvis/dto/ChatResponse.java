package com.example.jarvis.dto;

import java.util.List;

public record ChatResponse(String answer, List<ChatSource> sources) {
}
