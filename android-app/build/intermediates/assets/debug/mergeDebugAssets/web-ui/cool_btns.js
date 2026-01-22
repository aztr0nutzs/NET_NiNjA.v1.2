class RadioButtonEffect {
  constructor(radioBtnGroups) {
    this.previousRadioBtn = null;

    radioBtnGroups.forEach((group) => {
      const radioBtn = gsap.utils.selector(group)("input[type='radio']")[0];
      const nodes = this.getNodes(radioBtn);

      radioBtn.addEventListener("change", () => {
        if (this.previousRadioBtn && this.previousRadioBtn !== radioBtn) {
          this.changeEffect(this.getNodes(this.previousRadioBtn), false);
        }

        this.changeEffect(nodes, true);
        this.previousRadioBtn = radioBtn;
      });
    });
  }

  getNodes(radioBtn) {
    const container = radioBtn.closest(".radio-btn-group");
    return gsap.utils.shuffle(gsap.utils.selector(container)("rect"));
  }

  changeEffect(nodes, isChecked) {
    gsap.to(nodes, {
      duration: 0.8,
      ease: "elastic.out(1, 0.3)",
      x: isChecked ? "100%" : "-100%",
      stagger: 0.01,
      overwrite: true
    });

    gsap.fromTo(
      nodes,
      {
        fill: "#0c79f7"
      },
      {
        fill: "#76b3fa",
        duration: 0.1,
        ease: "elastic.out(1, 0.3)",
        repeat: -1
      }
    );

    if (isChecked) {
      const randomNodes = nodes.slice(0, 5);
      gsap.to(randomNodes, {
        duration: 0.7,
        ease: "elastic.out(1, 0.1)",
        x: "100%",
        stagger: 0.1,
        repeatDelay: 1.5,
        repeat: -1
      });
    }
  }
}

document.addEventListener("DOMContentLoaded", () => {
  const radioBtnGroups = document.querySelectorAll(".radio-btn-group");
  new RadioButtonEffect(radioBtnGroups);
});