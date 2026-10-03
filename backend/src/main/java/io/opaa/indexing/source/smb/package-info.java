/**
 * The SMB connector (ADR-0040, Nachtrag SMB): a Windows file share read with smbj under a service
 * account (NTLM), configured folders as containers of the shared {@code io.opaa.indexing.filesync},
 * every run a full sync with modification time and size as the change feature. Links (symbolic
 * links, junctions, DFS) are not followed, and credentials only ever reach the server of {@code
 * sourceUrl}.
 */
package io.opaa.indexing.source.smb;
