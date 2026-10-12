package com.keyloop.unifiedservice.exception;

/**
 * Thrown when an entity referenced by a request does not exist. Mapped to 404.
 */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}
}
