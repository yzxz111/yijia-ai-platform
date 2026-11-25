DELETE FROM gateway.attachment_extraction_cache
WHERE metadata ? 'imageDataUris';
