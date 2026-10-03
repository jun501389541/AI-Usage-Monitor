package com.aiusage.monitor.bridge;

/**
 * Certificates, digests and pairing offers produced by the real Go Bridge, not by
 * hand. Regenerate with {@code bridge/bin/payloadvec} (see docs/PHASE-7-PLAN.md §10
 * for the run) — the two languages implement the same format from two files, and
 * this is the seam that says whether they still agree.
 */
final class GoVectors {

    static final String ALPHA_CERT_PEM = ""
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIIDgTCCAmmgAwIBAgIIGNrtnV7m8HgwDQYJKoZIhvcNAQELBQAwPDEYMBYGA1UE\n"
        + "ChMPQUkgVXNhZ2UgQnJpZGdlMSAwHgYDVQQDDBdicl9ncTFvc2QyaDNzZmxyY2ll\n"
        + "b2NuZzAeFw0yNjEwMDMwNTIzMjFaFw0zNjA5MzAwNTI0MjFaMDwxGDAWBgNVBAoT\n"
        + "D0FJIFVzYWdlIEJyaWRnZTEgMB4GA1UEAwwXYnJfZ3Exb3NkMmgzc2ZscmNpZW9j\n"
        + "bmcwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDfC4yJRL++BoBZJaNH\n"
        + "DHWCBArHgnTkITNGnD9DTDHJ9WEGM3DC6l/Xefcwb0AcubghV5Md4CTRAg1GFd2C\n"
        + "Kut1PtfRwBRu1bOFHNcx8ISBZpZD9IhBmumEveqjzpXtZ9k+7/q/ZlgaDyoedPzU\n"
        + "TJiY5xJeAHU3/xH1pVgPCXBv2bZzoj/lTMFt6SVqOxRzsAXSAm2qCGfmwn3W5Dlg\n"
        + "1RAQYouj784em4/om5+QO7pf/IBMr/QNf+7jCNJ3fBgmuJP0exU2KIWsy44l/rQX\n"
        + "Tq2/z+bMhJN4N0QwNpxUo2qmBo0M1Ryc2lv1bCnHhW9ydUv7zIKqPHRNThoK5EOf\n"
        + "IYiBAgMBAAGjgYYwgYMwDgYDVR0PAQH/BAQDAgKkMBMGA1UdJQQMMAoGCCsGAQUF\n"
        + "BwMBMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFMKnf4/So+Z/KQqthBlIEF/M\n"
        + "XXOkMCwGA1UdEQQlMCOCCWxvY2FsaG9zdIcEfwAAAYcQAAAAAAAAAAAAAAAAAAAA\n"
        + "ATANBgkqhkiG9w0BAQsFAAOCAQEAyc5nfCpwZ6TOKqNmz2Zt3rQuRDEBvtN/zlJC\n"
        + "0aUF/mRdkYqh5M/zil3PCB9icfC+TA1lyfcfkKoR7fi7EKggon3sVppZX7u56SZg\n"
        + "pP9vmeMmYmoiD3TkLPCOHpOKGCWYznHGeu/grPcs7hHI/6BsFDh9Io/zONSmGQar\n"
        + "NkqnzkjEhppQs9psYieDgYGuyWwmHd/lbBJAF+AdS5+2uuaoBqv0x1hCU0X9ycmr\n"
        + "1R9Gd9r6B9RujlXCvlpJ+jsiCPrt06fBT9aMVxSOvPQcDjhV8XPDVr8PEdAYUhwh\n"
        + "BIH8F86dEAwGA1bfxpQPbBV26GxpBzxyZEHlv98jlt8CelsMow==\n"
        + "-----END CERTIFICATE-----\n";

    static final String BETA_CERT_PEM = ""
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIIDgTCCAmmgAwIBAgIIGNrtnWgL55QwDQYJKoZIhvcNAQELBQAwPDEYMBYGA1UE\n"
        + "ChMPQUkgVXNhZ2UgQnJpZGdlMSAwHgYDVQQDDBdicl9mc2hnNmJwN29yMTJycXAy\n"
        + "ZzIyMDAeFw0yNjEwMDMwNTIzMjJaFw0zNjA5MzAwNTI0MjJaMDwxGDAWBgNVBAoT\n"
        + "D0FJIFVzYWdlIEJyaWRnZTEgMB4GA1UEAwwXYnJfZnNoZzZicDdvcjEycnFwMmcy\n"
        + "MjAwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDNp0N5Y9qK1sHfVSBk\n"
        + "ZYcDu3uqGQzUmJYxb5HU/caft00dHOO8YCI694VmbSXGDsG2Rvq7LniptWPVT3iA\n"
        + "bOwnsGxDEZxgy2phPzb1aDHuyokb6WonsGDNgrpr4pL+PdlvNUH3CTtjriJO7jKN\n"
        + "bx07/9XhM4+uqGGviRx1/C7pnS4IE/CcIzhlypmtr1uI0RnsGx6vQhIkc35G65cd\n"
        + "DoK7cA3t1KomVtzct53wrxaxw4kxp/WBYRdAwuhOy0Uzoi5S95GfHVsSQ7Ve/tZm\n"
        + "Pj5M9BZodI+Jm39x6W7UMPcTUxsqyNeGqDwASVmXYwD3S7eSwk0+ZtZE3es9xCSE\n"
        + "07XZAgMBAAGjgYYwgYMwDgYDVR0PAQH/BAQDAgKkMBMGA1UdJQQMMAoGCCsGAQUF\n"
        + "BwMBMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFO+7qnXt/u2KZaXpSxGCzn13\n"
        + "XGl3MCwGA1UdEQQlMCOCCWxvY2FsaG9zdIcEfwAAAYcQAAAAAAAAAAAAAAAAAAAA\n"
        + "ATANBgkqhkiG9w0BAQsFAAOCAQEAbkUvCFDknr8cHG1A3bABwwv0VFr3RcOYFyBJ\n"
        + "v9kyErzQZN0i9ZGFbob0ZHVbE1N79sYlGDb+0vt88rshjr2fKaL6v3TaVwkPIxh8\n"
        + "7zEBr5fVNiv4p7nzE0l68DCZDuG+86z2L3+oIA1MTAgXBPFSP8PgPPEFUXfSTZSA\n"
        + "ehn//smy2JlGyIr4SAfwoL6lXuiP7W7cTNmWqsFQ4rP5h6uFPTdThDcSQAluuNmg\n"
        + "3dMdpQG4EZex4rSagGQXvVKAUq9ehz6Lye1RAKBR7pY9aTZ4NPEVPF7ZNLlY4FZV\n"
        + "hp56Bu4fGN6eNKKXJ3msVFhICePYlytu6wVt39kqsZZ3ZnNtzA==\n"
        + "-----END CERTIFICATE-----\n";

    static final String ALPHA_FINGERPRINT = "94cfeca524d68047296c70d220f62aa205184ff6beac1e8936e30caaab8b1be0";
    static final String BETA_FINGERPRINT = "09748cc3c60a6f8d8e51b7204e5a44d09721a9885bf7ea8b87e9db2132f02876";
    static final String ALPHA_BRIDGE_ID = "br_gq1osd2h3sflrcieocng";
    static final String ALPHA_PAYLOAD = "aiusage://pair#eyJ2IjoxLCJicmlkZ2VJZCI6ImJyX2dxMW9zZDJoM3NmbHJjaWVvY25nIiwiaG9zdHMiOlsiMTkyLjE2OC4xLjIwIiwiMTI3LjAuMC4xIl0sInBvcnQiOjM4NDExLCJwYWlyVG9rZW4iOiJiZmZkNWI2YzJkOTA1ODBlNjA0MzYxZjEwYmE1YjhkYzhhOTQ4ZmM0ZDc0YWRmODI4MmUwOTdhMzMyYzYwMWUzIiwiZmluZ2VycHJpbnQiOiI5NGNmZWNhNTI0ZDY4MDQ3Mjk2YzcwZDIyMGY2MmFhMjA1MTg0ZmY2YmVhYzFlODkzNmUzMGNhYWFiOGIxYmUwIn0";

    static final String PAIR_TOKEN =
            "bffd5b6c2d90580e604361f10ba5b8dc8a948fc4d74adf8282e097a332c601e3";

    private GoVectors() {
    }
}
