package com.crm.travelcrm.portal.document.dto;

/** Bytes + metadata for streaming a document through the authenticated download endpoint. */
public record DocumentDownload(byte[] content, String contentType, String fileName) {}
