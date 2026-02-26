# OPAA Test Document

This is a test document used for integration testing of the OPAA document indexing pipeline.

## Document Indexing

The indexing process involves parsing documents, splitting them into chunks, generating embeddings, and storing the results in a PostgreSQL database with the pgvector extension.

## Supported Formats

OPAA supports the following document formats:

- Markdown (.md)
- Plain text (.txt)
- PDF (.pdf)
- Microsoft Word (.docx)
- Microsoft PowerPoint (.pptx)

## RAG Pipeline

The Retrieval-Augmented Generation pipeline works by embedding a user question, searching for similar document chunks via vector similarity, and then using an LLM to generate an answer based on the retrieved context.
