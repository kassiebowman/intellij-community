/*
 * Copyright (c) 2000-2005 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */
package com.intellij.util.indexing;

import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;

import java.util.Set;

/**
 * @author peter
 */
public class AdditionalIndexableFileSet implements IndexableFileSet {
  private final Set<VirtualFile> myRoots = new THashSet<VirtualFile>();

  public AdditionalIndexableFileSet() {
    for (IndexedRootsProvider provider : IndexedRootsProvider.EP_NAME.getExtensions()) {
      for (String url : provider.getRootsToIndex()) {
        ContainerUtil.addIfNotNull(VirtualFileManager.getInstance().findFileByUrl(url), myRoots);
      }
    }
  }

  public boolean isInSet(VirtualFile file) {
    for (final VirtualFile root : myRoots) {
      if (VfsUtil.isAncestor(root, file, false)) {
        return true;
      }
    }
    return false;
  }

  public void iterateIndexableFilesIn(VirtualFile file, ContentIterator iterator) {
    iterator.processFile(file);
    if (file.isDirectory()) {
      for (VirtualFile virtualFile : file.getChildren()) {
        iterateIndexableFilesIn(virtualFile, iterator);
      }
    }
  }
}
