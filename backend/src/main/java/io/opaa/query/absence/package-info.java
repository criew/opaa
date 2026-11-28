/**
 * The check of an answer that claims something is missing from the sources: the claim is recognised
 * in the text, the question's key terms are searched once more within the request's search scope,
 * and passages the answer had not seen go into one further answer
 * (docs/handbuch/referenz/wie-die-suche-arbeitet.md).
 */
package io.opaa.query.absence;
