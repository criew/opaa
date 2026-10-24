/**
 * The Microsoft Graph drive connectors over {@code io.opaa.msgraph} and the file sync of {@code
 * indexing.filesync}, absence proven by the change log: SharePoint document libraries with the
 * client-credentials sign-in of a profile (ADR-0047), and the OneDrive for Business of a person
 * with her delegated OAuth consent (ADR-0058). Both share {@link
 * io.opaa.indexing.source.sharepoint.DriveFileStore}.
 */
package io.opaa.indexing.source.sharepoint;
