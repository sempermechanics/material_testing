"""Device keys for tests that register a phone.

One copy, imported by every test file that needs one (TD-131); a test module
importing a helper from another test module broke collection whenever that
module was refactored.
"""
import hashlib

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec


def _ec_pem() -> str:
    priv = ec.generate_private_key(ec.SECP256R1())
    return priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()


#: The order of P-256, for turning a seed into a valid private scalar.
_P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def seeded_p256_key(seed: str) -> ec.EllipticCurvePrivateKey:
    """A P-256 key derived from `seed`: TEST ONLY, and public by construction.

    The entitlement test vector (`fixtures/entitlement_vector.json`) names its
    seeds, so anyone can rebuild its keys, and no private key sits in the
    repo as PEM for a secret scanner to flag or anyone to mistake for real.
    Never use one of these keys outside tests."""
    scalar = int.from_bytes(hashlib.sha256(seed.encode("utf-8")).digest(), "big")
    return ec.derive_private_key(scalar % (_P256_ORDER - 1) + 1, ec.SECP256R1())


def public_pem(key: ec.EllipticCurvePrivateKey) -> str:
    return key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
