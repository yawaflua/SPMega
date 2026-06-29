using SPWorldsApi.Types.Interfaces;

namespace SpMega.Backend.Persistent.Models.DTO;

public class UserAccountDTO
{
    public string id { get; set; }
    public string username { get; set; }
    public string minecraftUUID { get; set; }
    public string status { get; set; }
    public List<string> roles { get; set; }
    public CityDTO city { get; set; }
    public List<UserCardDTO> cards { get; set; }
    public DateTime createdAt { get; set; }
}

public class UserCardDTO
{
    public string id { get; set; }
    public string name { get; set; }
    public string number { get; set; }
    public int color { get; set; }
}

public class CityDTO
{
    public string id { get; set; }
    public string name { get; set; }
    public string description { get; set; }
    public int x { get; set; }
    public int z { get; set; }
    public bool isMayor { get; set; }
}
