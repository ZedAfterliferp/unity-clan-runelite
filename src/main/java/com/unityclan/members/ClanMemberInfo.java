
package com.unityclan.members;

public class ClanMemberInfo
{
    private final String name;
    private final String rank;
    private final int world;

    public ClanMemberInfo(String name, String rank, int world)
    {
        this.name = name;
        this.rank = rank;
        this.world = world;
    }

    public String getName()
    {
        return name;
    }

    public String getRank()
    {
        return rank;
    }

    public int getWorld()
    {
        return world;
    }
}
