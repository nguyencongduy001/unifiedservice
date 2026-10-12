package com.keyloop.unifiedservice.dto;

import java.util.Map;

public record ApiError(

		int status,

		String error,

		String message,

		Map<String, String> fieldErrors) {
}
