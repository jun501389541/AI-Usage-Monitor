// Package filestore writes a file so that a reader either sees the whole old
// content or the whole new one.
//
// A half-written state file reads as "no data" to the next open, which is the
// exact failure the Bridge must not have: for the identity it would change the
// pinned fingerprint, for the quota cache it would erase the last good reading,
// for pairing it would revoke devices nobody revoked. So: a unique temporary file
// in the same directory, flushed, then renamed over the target, with the
// temporary removed on every error path.
package filestore

import (
	"fmt"
	"os"
	"path/filepath"
)

// Write stores data at path with the given permission bits. Callers that hold no
// higher-level lock should not call it concurrently for the same path: the rename
// is atomic, but on Windows a second rename onto the same target is refused, and
// a caller that ignores that error would believe its write landed.
func Write(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".tmp-*")
	if err != nil {
		return fmt.Errorf("creating a temporary file in %s: %w", dir, err)
	}
	name := tmp.Name()
	defer func() { _ = os.Remove(name) }()

	if err := tmp.Chmod(mode); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("setting %s to mode %o: %w", name, mode.Perm(), err)
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("writing %s: %w", name, err)
	}
	// Sync before the rename: without it the bytes can still be in the OS cache
	// when the directory entry is durable, so a power loss leaves an empty file
	// under the real name.
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("flushing %s: %w", name, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("closing %s: %w", name, err)
	}
	if err := os.Rename(name, path); err != nil {
		return fmt.Errorf("placing %s: %w", path, err)
	}
	return nil
}
