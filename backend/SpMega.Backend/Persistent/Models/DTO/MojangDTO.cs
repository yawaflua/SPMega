namespace SpMega.Backend.Persistent.Models.DTO;

public class PropertyDto
{
    public string Name { get; set; }
    public string Value { get; set; }
    public string Signature { get; set; }
}

public class MojangDto
{
    public string Id { get; set; }
    public string Name { get; set; }
    public List<PropertyDto> Properties { get; set; }
}