def posted(key="synthetic-key", at=100, **fields):
    return {"key": key, "package_name": "synthetic.package", "title": "Synthetic title", "text": "Synthetic text",
            "sub_text": "Synthetic sub-text", "posted_at": at, "ongoing": False, **fields}


def removed(key="synthetic-key", at=101, **fields):
    return {"key": key, "package_name": "synthetic.package", "removed_at": at, **fields}
