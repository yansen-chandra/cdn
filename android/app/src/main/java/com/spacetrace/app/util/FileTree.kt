package com.spacetrace.app.util

import com.spacetrace.app.model.FileEntry
import java.io.File

/** One folder or file in a pruned tree -- only branches leading to a matched file exist. */
data class TreeNode(
    val path: String,
    val name: String,
    val isFile: Boolean,
    val depth: Int,
    val entry: FileEntry? = null,
    val children: List<TreeNode> = emptyList(),
    val fileCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val latestCreatedAtMillis: Long = 0L,
)

object FileTree {

    private class Builder(val path: String, val name: String, val depth: Int) {
        val children = LinkedHashMap<String, Builder>()
        val directFiles = mutableListOf<FileEntry>()
        var fileCount = 0
        var totalSize = 0L
        var latestCreatedAt = 0L
    }

    /**
     * Builds a folder tree containing only the branches that lead to at least one of [files] --
     * unlike a full filesystem walk, folders with nothing "recent" in them don't appear at all.
     * Every level is sorted by its most recently created descendant first, so the tree stays
     * true to "Recent" rather than falling back to alphabetical order.
     */
    fun build(files: List<FileEntry>, roots: List<String>): List<TreeNode> {
        val builders = HashMap<String, Builder>()
        val rootPathsInTree = LinkedHashSet<String>()

        for (file in files) {
            // Ancestor chain from the file's parent folder up to (and including) its storage root.
            val chain = ArrayList<String>()
            var p: String? = file.parentPath
            while (p != null) {
                chain.add(p)
                if (roots.contains(p)) break
                p = File(p).parent
            }
            if (chain.isEmpty()) continue
            chain.reverse() // root first

            rootPathsInTree.add(chain.first())

            var parent: Builder? = null
            for ((depth, path) in chain.withIndex()) {
                val node = builders.getOrPut(path) {
                    Builder(path, File(path).name.ifEmpty { path }, depth)
                }
                parent?.children?.putIfAbsent(path, node)
                parent = node
            }
            parent?.directFiles?.add(file)

            for (path in chain) {
                val b = builders.getValue(path)
                b.fileCount++
                b.totalSize += file.sizeBytes
                if (file.createdAtMillis > b.latestCreatedAt) b.latestCreatedAt = file.createdAtMillis
            }
        }

        fun toNode(b: Builder): TreeNode {
            val folderNodes = b.children.values.map(::toNode)
            val fileNodes = b.directFiles.map { fe ->
                TreeNode(
                    path = fe.path,
                    name = fe.name,
                    isFile = true,
                    depth = b.depth + 1,
                    entry = fe,
                    fileCount = 1,
                    totalSizeBytes = fe.sizeBytes,
                    latestCreatedAtMillis = fe.createdAtMillis,
                )
            }
            val combined = (folderNodes + fileNodes).sortedByDescending { it.latestCreatedAtMillis }
            return TreeNode(
                path = b.path,
                name = b.name,
                isFile = false,
                depth = b.depth,
                children = combined,
                fileCount = b.fileCount,
                totalSizeBytes = b.totalSize,
                latestCreatedAtMillis = b.latestCreatedAt,
            )
        }

        // Roots themselves aren't shown as a node (e.g. no "storage/emulated/0" row) --
        // only their contents, so multiple storage volumes interleave naturally too.
        return rootPathsInTree
            .mapNotNull { builders[it] }
            .flatMap { toNode(it).children }
            .sortedByDescending { it.latestCreatedAtMillis }
    }

    /**
     * The chain of folder paths (immediate parent first, storage root last) that must all be
     * expanded for a file directly inside [fileParentPath] to be visible -- i.e. every ancestor
     * [build] would have walked for that file.
     */
    fun ancestorPaths(fileParentPath: String, roots: List<String>): List<String> {
        val chain = ArrayList<String>()
        var p: String? = fileParentPath
        while (p != null) {
            chain.add(p)
            if (roots.contains(p)) break
            p = File(p).parent
        }
        return chain
    }

    /** Flattens [nodes] into the currently-visible rows given which folder paths are expanded. */
    fun flattenVisible(nodes: List<TreeNode>, expanded: Set<String>): List<TreeNode> {
        val out = ArrayList<TreeNode>()
        fun visit(list: List<TreeNode>) {
            for (node in list) {
                out.add(node)
                if (!node.isFile && expanded.contains(node.path)) {
                    visit(node.children)
                }
            }
        }
        visit(nodes)
        return out
    }
}
