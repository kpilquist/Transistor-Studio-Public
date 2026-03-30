package org.example;

import java.util.Arrays;

/**
 * Represents a MapPacket for the NarrowGauge protocol.
 * Used to sync files using an adaptive Merkle Tree approach.
 */
public class MapPacket {
    private int commandID;
    private int treeLevel;
    private int blockIndex;
    private int branchCount;
    private long[] hashes; // Assuming xxHash64

    public MapPacket() {
    }

    public MapPacket(int commandID, int treeLevel, int blockIndex, int branchCount, long[] hashes) {
        this.commandID = commandID;
        this.treeLevel = treeLevel;
        this.blockIndex = blockIndex;
        this.branchCount = branchCount;
        this.hashes = hashes;
    }

    public int getCommandID() {
        return commandID;
    }

    public void setCommandID(int commandID) {
        this.commandID = commandID;
    }

    public int getTreeLevel() {
        return treeLevel;
    }

    public void setTreeLevel(int treeLevel) {
        this.treeLevel = treeLevel;
    }

    public int getBlockIndex() {
        return blockIndex;
    }

    public void setBlockIndex(int blockIndex) {
        this.blockIndex = blockIndex;
    }

    public int getBranchCount() {
        return branchCount;
    }

    public void setBranchCount(int branchCount) {
        this.branchCount = branchCount;
    }

    public long[] getHashes() {
        return hashes;
    }

    public void setHashes(long[] hashes) {
        this.hashes = hashes;
    }

    @Override
    public String toString() {
        return "MapPacket{" +
                "commandID=" + commandID +
                ", treeLevel=" + treeLevel +
                ", blockIndex=" + blockIndex +
                ", branchCount=" + branchCount +
                ", hashes=" + Arrays.toString(hashes) +
                '}';
    }
}
