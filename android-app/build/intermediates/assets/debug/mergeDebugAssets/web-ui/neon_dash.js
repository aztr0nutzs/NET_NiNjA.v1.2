import * as THREE from "https://esm.sh/three";

// APPLICATION STATE 
let tasks = [];
let subscriptions = [];

// DOM ELEMENT SELECTORS 
const taskForm = document.getElementById('add-task-form');
const taskTitleInput = document.getElementById('task-title-input');
const taskCategoryInput = document.getElementById('task-category-input');
const taskList = document.getElementById('task-list');

const subForm = document.getElementById('add-sub-form');
const subNameInput = document.getElementById('sub-name-input');
const subPriceInput = document.getElementById('sub-price-input');
const subscriptionList = document.getElementById('subscription-list');
const subscriptionTotalEl = document.getElementById('subscription-total');

// DATA PERSISTENCE (localStorage) 
const saveData = () => {
    localStorage.setItem('zenith_tasks', JSON.stringify(tasks));
    localStorage.setItem('zenith_subscriptions', JSON.stringify(subscriptions));
};

const loadData = () => {
    const savedTasks = JSON.parse(localStorage.getItem('zenith_tasks'));
    const savedSubs = JSON.parse(localStorage.getItem('zenith_subscriptions'));
    // If there is saved data, use it. Otherwise, initialize with default examples.
    tasks = savedTasks || [
        { id: 1665993600000, title: "Finalize Project Orion Report", category: "Work", completed: false },
        { id: 1666006200001, title: "Review architectural mockups", category: "Work", completed: true }
    ];
    subscriptions = savedSubs || [
        { id: 1665849600000, name: "VectorFlow AI", price: 49.99 },
        { id: 1665936000000, name: "ChromaCloud Storage", price: 9.99 }
    ];
};

// TEMPLATE FUNCTIONS 
const taskTemplate = (task) => {
    const timestamp = new Date(task.id);
    const formattedDate = timestamp.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
    const formattedTime = timestamp.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
    const isCompleted = task.completed ? 'completed' : '';
    const isChecked = task.completed ? 'checked' : '';
    
    return `
        <div class="task-item ${isCompleted}" data-id="${task.id}">
            <input type="checkbox" class="task-checkbox" ${isChecked} title="Mark as complete">
            <div class="item-info"><h4>${task.title}</h4><p>${task.category}</p></div>
            <div class="item-timestamp"><span>${formattedDate}</span><br><span>${formattedTime}</span></div>
            <button class="delete-btn" title="Delete Task">&times;</button>
        </div>`;
};

const subscriptionTemplate = (sub) => `
    <div class="subscription-item" data-id="${sub.id}">
        <div class="item-info"><h4>${sub.name}</h4></div>
        <div class="price">$${sub.price.toFixed(2)}</div>
        <button class="delete-btn" title="Delete Subscription">&times;</button>
    </div>`;

// RENDER & UPDATE FUNCTIONS 
const renderTasks = () => {
    const sortedTasks = [...tasks].sort((a, b) => a.completed - b.completed);
    if (sortedTasks.length === 0) taskList.innerHTML = `<p class="empty-message">No entries.</p>`;
    else taskList.innerHTML = sortedTasks.map(taskTemplate).join('');
};

const renderSubscriptions = () => {
    if (subscriptions.length === 0) subscriptionList.innerHTML = `<p class="empty-message">No entries.</p>`;
    else subscriptionList.innerHTML = subscriptions.map(subscriptionTemplate).join('');
    updateSubscriptionTotal();
};

const updateSubscriptionTotal = () => {
    const total = subscriptions.reduce((sum, sub) => sum + sub.price, 0);
    subscriptionTotalEl.textContent = `$${total.toFixed(2)}`;
};

// EVENT HANDLERS 
const handleAddTask = (e) => {
    e.preventDefault();
    const title = taskTitleInput.value.trim();
    if (!title) return;
    tasks.unshift({ id: Date.now(), title, category: taskCategoryInput.value, completed: false });
    saveData();
    renderTasks();
    taskForm.reset();
};

const handleAddSubscription = (e) => {
    e.preventDefault();
    const name = subNameInput.value.trim();
    const price = parseFloat(subPriceInput.value);
    if (!name || isNaN(price)) return;
    subscriptions.unshift({ id: Date.now(), name, price });
    saveData();
    renderSubscriptions();
    subForm.reset();
};

const handleTaskListClick = (e) => {
    const target = e.target;
    const itemEl = target.closest('[data-id]');
    if (!itemEl) return;
    const itemId = Number(itemEl.getAttribute('data-id'));

    if (target.classList.contains('delete-btn')) {
        itemEl.classList.add('removing');
        setTimeout(() => {
            tasks = tasks.filter(task => task.id !== itemId);
            saveData();
            renderTasks();
        }, 400);
    } else if (target.classList.contains('task-checkbox')) {
        const task = tasks.find(t => t.id === itemId);
        if (task) {
            task.completed = !task.completed;
            saveData();
            renderTasks();
        }
    }
};

const handleSubscriptionListClick = (e) => {
    if (e.target.classList.contains('delete-btn')) {
        const itemEl = e.target.closest('[data-id]');
        const itemId = Number(itemEl.getAttribute('data-id'));
        itemEl.classList.add('removing');
        setTimeout(() => {
            subscriptions = subscriptions.filter(sub => sub.id !== itemId);
            saveData();
            renderSubscriptions();
        }, 400);
    }
};

// UTILITY & INITIALIZATION 
function updateDateTime() {
    const now = new Date();
    document.getElementById('time').textContent = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    document.getElementById('date').textContent = now.toLocaleDateString([], { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
}

document.addEventListener('DOMContentLoaded', () => {
    loadData();

    taskForm.addEventListener('submit', handleAddTask);
    subForm.addEventListener('submit', handleAddSubscription);
    taskList.addEventListener('click', handleTaskListClick);
    subscriptionList.addEventListener('click', handleSubscriptionListClick);
    
    updateDateTime();
    setInterval(updateDateTime, 1000);
    renderTasks();
    renderSubscriptions();
});


// THREE.JS BACKGROUND 
let scene, camera, renderer, structuralGirders;
function init() {
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
    camera.position.z = 5;
    camera.position.y = 2;
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setSize(window.innerWidth, window.innerHeight);
    document.getElementById('three-js-canvas').appendChild(renderer.domElement);
    const ambientLight = new THREE.AmbientLight(0x404080, 2);
    scene.add(ambientLight);
    const pointLight1 = new THREE.PointLight(0x00e5ff, 50, 100);
    pointLight1.position.set(-10, 5, -5);
    scene.add(pointLight1);
    const pointLight2 = new THREE.PointLight(0xff00c1, 50, 100);
    pointLight2.position.set(10, 5, 5);
    scene.add(pointLight2);
    structuralGirders = new THREE.Group();
    const girderMaterial = new THREE.MeshStandardMaterial({ color: 0x1a1a2a, roughness: 0.5 });
    for (let i = 0; i < 10; i++) {
        const height = Math.random() * 15 + 5;
        const girder = new THREE.Mesh(new THREE.BoxGeometry(0.2, height, 0.2), girderMaterial);
        girder.position.x = (Math.random() - 0.5) * 20;
        girder.position.z = (Math.random() - 0.5) * 20;
        girder.position.y = height / 2 - 5;
        structuralGirders.add(girder);
    }
    scene.add(structuralGirders);
    const floorGeometry = new THREE.PlaneGeometry(50, 50);
    const floorMaterial = new THREE.MeshStandardMaterial({ color: 0x0a0a14, metalness: 0.8, roughness: 0.3 });
    const floor = new THREE.Mesh(floorGeometry, floorMaterial);
    floor.rotation.x = -Math.PI / 2;
    floor.position.y = -5;
    scene.add(floor);
    window.addEventListener('resize', onWindowResize, false);
    document.addEventListener('mousemove', onMouseMove, false);
}
function onWindowResize() {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
}
let mouseX = 0, mouseY = 0;
function onMouseMove(event) {
    mouseX = (event.clientX / window.innerWidth) * 2 - 1;
    mouseY = -(event.clientY / window.innerHeight) * 2 + 1;
}
function animate() {
    requestAnimationFrame(animate);
    camera.position.x += (mouseX * 0.5 - camera.position.x) * 0.05;
    camera.position.y += (-mouseY * 0.5 - camera.position.y + 2) * 0.05;
    camera.lookAt(scene.position);
    structuralGirders.rotation.y += 0.0005;
    renderer.render(scene, camera);
}
init();
animate();