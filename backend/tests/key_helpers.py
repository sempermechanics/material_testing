"""Device keys for tests that register a phone.

One copy, imported by every test file that needs one (TD-131); a test module
importing a helper from another test module broke collection whenever that
module was refactored.
"""
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec


def _ec_pem() -> str:
    priv = ec.generate_private_key(ec.SECP256R1())
    return priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
