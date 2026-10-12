package com.keyloop.unifiedservice.exception;

/**
 * Thrown when a booking cannot be confirmed because no service bay or no
 * qualified technician is free for the whole service duration. Mapped to 409.
 */
public class NoResourceAvailableException extends RuntimeException {

	public NoResourceAvailableException(String message) {
		super(message);
	}
}
