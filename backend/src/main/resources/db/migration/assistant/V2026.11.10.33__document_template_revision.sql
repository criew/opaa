SET LOCAL lock_timeout = '5s';

-- The revision ("Fassung") of a document template: counts every replacement of its file, so an
-- agent answer and its result file can name the revision they filled. Nullable without default,
-- purely additive: a row without a value - written before this column or by a version that does
-- not know it yet - is read as revision 1.

ALTER TABLE document_templates ADD COLUMN revision integer;
