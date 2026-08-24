package io.opaa.library;

/**
 * One direct subfolder of a browsed folder, paired with its own recursive document count (#821) -
 * its own documents plus every document in every descendant folder.
 */
public record LibraryFolderChild(LibraryFolder folder, long documentCount) {}
