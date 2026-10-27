package cube.util.tokenizer;

public class Node {
    /** 值。 */
    public Character value;
    /** 父母。 */
    public Node parent;

    public Node(Character value, Node parent) {
        this.value = value;
        this.parent = parent;
    }
}
