# Herkunft der MSG-Testfixturen

`simple_test_msg.msg` und `attachment_msg_pdf.msg` stammen aus dem Testkorpus des
Apache-POI-Projekts (`test-data/hsmf/`), Apache License 2.0:
<https://github.com/apache/poi/tree/trunk/test-data/hsmf>

Real, valide `.msg`-Dateien lassen sich nicht handschreiben (proprietäres OLE2/MAPI-Binärformat)
und Apache POI selbst bietet keinen `.msg`-Writer - diese beiden Fixturen sind deshalb aus einem
etablierten Open-Source-Testkorpus übernommen statt synthetisch erzeugt.
